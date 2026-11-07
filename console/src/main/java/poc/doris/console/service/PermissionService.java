package poc.doris.console.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.console.domain.ClusterRepository;
import poc.doris.console.domain.DorisClusterSync;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisHost;
import poc.doris.console.domain.DorisHostRepository;
import poc.doris.console.domain.DorisSaGrant;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.domain.WorkspaceDorisRepository;
import poc.doris.console.domain.WorkspaceRepository;

/**
 * The only place Doris permissions are changed. Every change is written together with a
 * doris_sync_outbox event for the affected fab, in one transaction (sync segment 1).
 */
@Service
@Transactional
public class PermissionService {

    public static final Set<String> PRIVILEGES =
            Set.of("SELECT_PRIV", "LOAD_PRIV", "ALTER_PRIV", "CREATE_PRIV", "DROP_PRIV");

    // Names end up in GRANT statements on the Cluster side, so only plain identifiers are accepted.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z0-9-]{1,64}");

    private final ClusterRepository clusters;
    private final WorkspaceRepository workspaces;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaGrantRepository grants;
    private final DorisSyncOutboxRepository outbox;
    private final DorisClusterSyncRepository syncs;
    private final CredentialCipher cipher;

    public PermissionService(ClusterRepository clusters, WorkspaceRepository workspaces, DorisHostRepository hosts,
            WorkspaceDorisRepository paths, DorisSaGrantRepository grants, DorisSyncOutboxRepository outbox,
            DorisClusterSyncRepository syncs, CredentialCipher cipher) {
        this.clusters = clusters;
        this.workspaces = workspaces;
        this.hosts = hosts;
        this.paths = paths;
        this.grants = grants;
        this.outbox = outbox;
        this.syncs = syncs;
        this.cipher = cipher;
    }

    public DorisHost registerHost(String clusterName, String instanceName, String feHost, int queryPort,
            int httpPort, String secretRef) {
        requireCluster(clusterName);
        require(INSTANCE_NAME.matcher(instanceName).matches(), "instance_name 只能用小寫英數與 -");
        require(!feHost.isBlank() && !secretRef.isBlank(), "fe_host 與 secret_ref 不能空白");
        if (hosts.existsByInstanceName(instanceName)) {
            throw conflict("instance_name " + instanceName + " 已存在");
        }
        if (hosts.existsByFeHostAndQueryPort(feHost, queryPort)) {
            throw conflict(feHost + ":" + queryPort + " 已登記");
        }
        DorisHost host = hosts.save(new DorisHost(clusterName, instanceName, feHost, queryPort, httpPort, secretRef));
        enqueueSync(clusterName);
        return host;
    }

    public void deleteHost(String hostId) {
        DorisHost host = hosts.findById(hostId).orElseThrow(() -> notFound("Doris host"));
        if (paths.existsByDorisHostId(hostId)) {
            throw conflict(host.getInstanceName() + " 仍有 workspace 的 doris path 指向它");
        }
        hosts.delete(host);
        enqueueSync(host.getClusterName());
    }

    /** Opens a workspace's doris path in one fab: creates its SA with a fresh, encrypted password. */
    public WorkspaceDoris openPath(String workspaceName, String clusterName, String hostId) {
        requireCluster(clusterName);
        if (!workspaces.existsById(workspaceName)) {
            throw notFound("workspace " + workspaceName);
        }
        DorisHost host = hosts.findById(hostId).orElseThrow(() -> notFound("Doris host"));
        require(host.getClusterName().equals(clusterName),
                host.getInstanceName() + " 屬於 " + host.getClusterName() + "，不是 " + clusterName);
        if (paths.findByWorkspaceNameAndClusterName(workspaceName, clusterName).isPresent()) {
            throw conflict(workspaceName + " 在 " + clusterName + " 已經有 doris path");
        }
        String username = "sa_" + workspaceName.replaceAll("[^A-Za-z0-9]", "_");
        if (paths.existsByDorisHostIdAndUsername(hostId, username)) {
            throw conflict(host.getInstanceName() + " 上已有 SA " + username);
        }
        WorkspaceDoris path = paths.save(new WorkspaceDoris(workspaceName, clusterName, hostId, username,
                cipher.encrypt(cipher.newPassword()), cipher.keyVersion()));
        enqueueSync(clusterName);
        return path;
    }

    /** Decision 4: a doris path with grants cannot be cleared, so no orphan SA is left on Doris. */
    public void clearPath(String workspaceName, String clusterName) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        if (grants.countByWorkspaceDorisId(path.getId()) > 0) {
            throw conflict(workspaceName + " 在 " + clusterName + " 還有授權，請先移除所有授權");
        }
        paths.delete(path);
        enqueueSync(clusterName);
    }

    public DorisSaGrant addGrant(String workspaceName, String clusterName, String catalog, String database,
            String table, String privilege) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        require(IDENTIFIER.matcher(catalog).matches(), "catalog 名稱不合法");
        require(IDENTIFIER.matcher(database).matches(), "database 名稱不合法");
        require(table.equals("*") || IDENTIFIER.matcher(table).matches(), "table 名稱不合法（整個 database 用 *）");
        require(PRIVILEGES.contains(privilege), "不支援的權限 " + privilege);
        if (grants.existsByWorkspaceDorisIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
                path.getId(), catalog, database, table, privilege)) {
            throw conflict("已經有這筆授權");
        }
        DorisSaGrant grant = grants.save(new DorisSaGrant(path.getId(), catalog, database, table, privilege));
        enqueueSync(clusterName);
        return grant;
    }

    public void removeGrant(String workspaceName, String clusterName, long grantId) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        DorisSaGrant grant = grants.findById(grantId)
                .filter(g -> g.getWorkspaceDorisId().equals(path.getId()))
                .orElseThrow(() -> notFound("授權"));
        grants.delete(grant);
        enqueueSync(clusterName);
    }

    /**
     * Copies a workspace's grants in one fab to other fabs where it already has a doris path. Only missing
     * grants are added; grants the target has beyond the source stay. Returns how many grants were added.
     */
    public int copyGrants(String workspaceName, String fromCluster, List<String> toClusters) {
        WorkspaceDoris from = requirePath(workspaceName, fromCluster);
        require(!toClusters.isEmpty(), "請選擇要複製到的 fab");
        require(!toClusters.contains(fromCluster), "不能複製到自己");
        List<DorisSaGrant> source =
                grants.findByWorkspaceDorisIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(
                        from.getId());
        int added = 0;
        for (String cluster : toClusters) {
            WorkspaceDoris to = requirePath(workspaceName, cluster);
            int before = added;
            for (DorisSaGrant g : source) {
                if (!grants.existsByWorkspaceDorisIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
                        to.getId(), g.getCatalogName(), g.getDatabaseName(), g.getTableName(), g.getPrivilege())) {
                    grants.save(new DorisSaGrant(to.getId(), g.getCatalogName(), g.getDatabaseName(),
                            g.getTableName(), g.getPrivilege()));
                    added++;
                }
            }
            if (added > before) {
                enqueueSync(cluster);
            }
        }
        return added;
    }

    /** Bumps the fab's desired_version and records an outbox event for it, in the caller's transaction. */
    private void enqueueSync(String clusterName) {
        DorisClusterSync sync = syncs.findForUpdate(clusterName)
                .orElseGet(() -> syncs.save(new DorisClusterSync(clusterName)));
        outbox.save(new DorisSyncOutbox(clusterName, sync.nextDesiredVersion()));
    }

    /** Decision 3: writes for a workspace without a doris path in that fab are rejected with 403. */
    private WorkspaceDoris requirePath(String workspaceName, String clusterName) {
        return paths.findByWorkspaceNameAndClusterName(workspaceName, clusterName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        workspaceName + " 在 " + clusterName + " 沒有 doris path"));
    }

    private void requireCluster(String clusterName) {
        if (!clusters.existsById(clusterName)) {
            throw notFound("cluster " + clusterName);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到 " + what);
    }
}

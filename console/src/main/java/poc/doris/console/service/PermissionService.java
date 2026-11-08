package poc.doris.console.service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import poc.doris.console.domain.DorisSa;
import poc.doris.console.domain.DorisSaGrant;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.DorisSaRepository;
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
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Set<String> RESERVED_USERS = Set.of("root", "admin");

    private final ClusterRepository clusters;
    private final WorkspaceRepository workspaces;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaRepository sas;
    private final DorisSaGrantRepository grants;
    private final DorisSyncOutboxRepository outbox;
    private final DorisClusterSyncRepository syncs;
    private final CredentialCipher cipher;

    public PermissionService(ClusterRepository clusters, WorkspaceRepository workspaces, DorisHostRepository hosts,
            WorkspaceDorisRepository paths, DorisSaRepository sas, DorisSaGrantRepository grants,
            DorisSyncOutboxRepository outbox, DorisClusterSyncRepository syncs, CredentialCipher cipher) {
        this.clusters = clusters;
        this.workspaces = workspaces;
        this.hosts = hosts;
        this.paths = paths;
        this.sas = sas;
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

    /** A password shown once, right after it was generated; only the encrypted copy is stored. */
    public record NewPassword(DorisSa sa, String password) {
    }

    /** Opens a workspace's doris path in one fab: the whitelist that lets it have SAs on that fab's host. */
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
        WorkspaceDoris path = paths.save(new WorkspaceDoris(workspaceName, clusterName, hostId));
        enqueueSync(clusterName);
        return path;
    }

    /** Decision 4: a doris path that still has SAs cannot be cleared, so no orphan SA is left on Doris. */
    public void clearPath(String workspaceName, String clusterName) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        if (sas.countByWorkspaceDorisId(path.getId()) > 0) {
            throw conflict(workspaceName + " 在 " + clusterName + " 還有 SA，請先刪除所有 SA");
        }
        paths.delete(path);
        enqueueSync(clusterName);
    }

    /** Adds an SA to the doris path with a freshly generated password. */
    public NewPassword createSa(String workspaceName, String clusterName, String username, String description) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        require(USERNAME.matcher(username).matches(), "username 只能用英數與 _，最多 64 字元");
        require(!RESERVED_USERS.contains(username.toLowerCase()), username + " 是 Doris 保留的帳號");
        require(description.length() <= 255, "用途說明最多 255 字元");
        if (sas.existsByDorisHostIdAndUsername(path.getDorisHostId(), username)) {
            throw conflict("這座 Doris 上已經有 " + username);
        }
        String password = cipher.newPassword();
        DorisSa sa = sas.save(new DorisSa(path, username, description, cipher.encrypt(password),
                cipher.keyVersion()));
        enqueueSync(clusterName);
        return new NewPassword(sa, password);
    }

    /** Replaces the SA's password; once synced, the old one stops working and DAO sees a new cred_version. */
    public NewPassword regeneratePassword(String workspaceName, String clusterName, String saId) {
        DorisSa sa = requireSa(workspaceName, clusterName, saId);
        String password = cipher.newPassword();
        sa.newPassword(cipher.encrypt(password), cipher.keyVersion());
        enqueueSync(clusterName);
        return new NewPassword(sa, password);
    }

    /** Like decision 4: an SA that still has grants cannot be deleted. */
    public void deleteSa(String workspaceName, String clusterName, String saId) {
        DorisSa sa = requireSa(workspaceName, clusterName, saId);
        if (grants.countBySaId(saId) > 0) {
            throw conflict(sa.getUsername() + " 還有授權，請先移除所有授權");
        }
        sas.delete(sa);
        enqueueSync(clusterName);
    }

    public DorisSaGrant addGrant(String workspaceName, String clusterName, String saId, String catalog,
            String database, String table, String privilege) {
        DorisSa sa = requireSa(workspaceName, clusterName, saId);
        require(IDENTIFIER.matcher(catalog).matches(), "catalog 名稱不合法");
        require(IDENTIFIER.matcher(database).matches(), "database 名稱不合法");
        require(table.equals("*") || IDENTIFIER.matcher(table).matches(), "table 名稱不合法（整個 database 用 *）");
        require(PRIVILEGES.contains(privilege), "不支援的權限 " + privilege);
        if (grants.existsBySaIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
                sa.getId(), catalog, database, table, privilege)) {
            throw conflict(sa.getUsername() + " 已經有這筆授權");
        }
        DorisSaGrant grant = grants.save(new DorisSaGrant(sa.getId(), catalog, database, table, privilege));
        enqueueSync(clusterName);
        return grant;
    }

    public void removeGrant(String workspaceName, String clusterName, long grantId) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        DorisSaGrant grant = grants.findById(grantId)
                .filter(g -> sas.findById(g.getSaId()).map(DorisSa::getWorkspaceDorisId)
                        .filter(path.getId()::equals).isPresent())
                .orElseThrow(() -> notFound("授權"));
        grants.delete(grant);
        enqueueSync(clusterName);
    }

    /**
     * Copies an SA's grants to other SAs of the same workspace, in any fab. Only missing grants are added;
     * grants the target has beyond the source stay. Returns how many grants were added.
     */
    public int copyGrants(String workspaceName, String clusterName, String fromSaId, List<String> toSaIds) {
        DorisSa from = requireSa(workspaceName, clusterName, fromSaId);
        require(!toSaIds.isEmpty(), "請選擇要複製到的 SA");
        require(!toSaIds.contains(fromSaId), "不能複製到自己");
        List<DorisSaGrant> source = grants.findBySaIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(
                from.getId());
        Set<String> changedClusters = new TreeSet<>();
        int added = 0;
        for (String toSaId : toSaIds) {
            DorisSa to = sas.findById(toSaId).orElseThrow(() -> notFound("SA"));
            WorkspaceDoris toPath = paths.findById(to.getWorkspaceDorisId()).orElseThrow();
            require(toPath.getWorkspaceName().equals(workspaceName), to.getUsername() + " 不屬於 " + workspaceName);
            for (DorisSaGrant g : source) {
                if (!grants.existsBySaIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
                        to.getId(), g.getCatalogName(), g.getDatabaseName(), g.getTableName(), g.getPrivilege())) {
                    grants.save(new DorisSaGrant(to.getId(), g.getCatalogName(), g.getDatabaseName(),
                            g.getTableName(), g.getPrivilege()));
                    changedClusters.add(toPath.getClusterName());
                    added++;
                }
            }
        }
        changedClusters.forEach(this::enqueueSync);
        return added;
    }

    /** Bumps the fab's desired_version and records an outbox event for it, in the caller's transaction. */
    private void enqueueSync(String clusterName) {
        DorisClusterSync sync = syncs.findForUpdate(clusterName)
                .orElseGet(() -> syncs.save(new DorisClusterSync(clusterName)));
        outbox.save(new DorisSyncOutbox(clusterName, sync.nextDesiredVersion()));
    }

    private DorisSa requireSa(String workspaceName, String clusterName, String saId) {
        WorkspaceDoris path = requirePath(workspaceName, clusterName);
        return sas.findById(saId).filter(sa -> sa.getWorkspaceDorisId().equals(path.getId()))
                .orElseThrow(() -> notFound("SA"));
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

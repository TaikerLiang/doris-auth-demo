package poc.doris.cluster.service;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.cluster.domain.DorisApplyOutbox;
import poc.doris.cluster.domain.DorisApplyOutboxRepository;
import poc.doris.cluster.domain.DorisHost;
import poc.doris.cluster.domain.DorisHostRepository;
import poc.doris.cluster.domain.DorisSaGrant;
import poc.doris.cluster.domain.DorisSaGrantRepository;
import poc.doris.cluster.domain.DorisSyncState;
import poc.doris.cluster.domain.DorisSyncStateRepository;
import poc.doris.cluster.domain.WorkspaceDoris;
import poc.doris.cluster.domain.WorkspaceDorisRepository;
import poc.doris.cluster.service.ReconcilePlanner.DesiredSa;

/** The Cluster DB side of sync: accepts snapshots, hands the desired state to the reconciler, records results. */
@Service
@Transactional
public class StateService {

    public static final Set<String> PRIVILEGES =
            Set.of("SELECT_PRIV", "LOAD_PRIV", "ALTER_PRIV", "CREATE_PRIV", "DROP_PRIV");

    // Everything below ends up inside SQL statements or Redis keys, so only plain values are accepted.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    // Console builds "sa_" + workspace name (up to 64), and its username column is VARCHAR(128)
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9.-]{1,255}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z0-9_]{1,128}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9]{16,128}");

    /** The desired state of one Doris host, with plaintext passwords; only lives in memory. */
    public record HostState(DorisHost host, List<DesiredSa> sas) {
    }

    public record Desired(long version, List<HostState> hosts) {
    }

    private final String clusterName;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaGrantRepository grants;
    private final DorisSyncStateRepository states;
    private final DorisApplyOutboxRepository outbox;
    private final CredentialCipher cipher;
    private final EntityManager entityManager;

    public StateService(@Value("${cluster.name}") String clusterName, DorisHostRepository hosts,
            WorkspaceDorisRepository paths, DorisSaGrantRepository grants, DorisSyncStateRepository states,
            DorisApplyOutboxRepository outbox, CredentialCipher cipher, EntityManager entityManager) {
        this.clusterName = clusterName;
        this.hosts = hosts;
        this.paths = paths;
        this.grants = grants;
        this.states = states;
        this.outbox = outbox;
        this.cipher = cipher;
        this.entityManager = entityManager;
    }

    /**
     * Overwrites the Cluster DB with the snapshot and queues an apply event, in one transaction.
     * Returns false when the snapshot is not newer than what was already received (resends are harmless).
     */
    public boolean receive(Snapshot snapshot) {
        require(clusterName.equals(snapshot.cluster()), "這裡是 " + clusterName + "，不是 " + snapshot.cluster());
        DorisSyncState state = lockState();
        if (snapshot.version() <= state.getReceivedVersion()) {
            return false;
        }
        validate(snapshot);
        grants.deleteAllInBatch();
        paths.deleteAllInBatch();
        hosts.deleteAllInBatch();
        // Bulk deletes bypass the persistence context; drop its stale copies before saving the same ids again
        entityManager.clear();
        state = lockState();
        snapshot.hosts().forEach(h -> hosts.save(
                new DorisHost(h.id(), h.instanceName(), h.feHost(), h.queryPort(), h.httpPort(), h.secretRef())));
        snapshot.paths().forEach(p -> paths.save(new WorkspaceDoris(p.saId(), p.workspace(), p.hostId(),
                p.username(), cipher.encrypt(p.password()), cipher.keyVersion())));
        snapshot.grants().forEach(g -> grants.save(
                new DorisSaGrant(g.saId(), g.catalog(), g.database(), g.table(), g.privilege())));
        state.received(snapshot.version());
        outbox.save(new DorisApplyOutbox(snapshot.version()));
        return true;
    }

    /** Manual trigger: apply the current Cluster DB state again, e.g. to repair drift. */
    public void enqueueApply() {
        outbox.save(new DorisApplyOutbox(lockState().getReceivedVersion()));
    }

    @Transactional(readOnly = true)
    public boolean hasPendingApply() {
        return !outbox.findByStatusOrderById(DorisApplyOutbox.PENDING).isEmpty();
    }

    /** Read in one transaction so hosts, paths and grants come from the same snapshot. */
    @Transactional(readOnly = true)
    public Desired loadDesired() {
        long version = states.findById(clusterName).map(DorisSyncState::getReceivedVersion).orElse(0L);
        Map<String, Set<DorisGrant>> grantsBySa = grants.findAll().stream().collect(Collectors.groupingBy(
                DorisSaGrant::getWorkspaceDorisId, Collectors.mapping(g -> new DorisGrant(g.getCatalogName(),
                        g.getDatabaseName(), g.getTableName(), g.getPrivilege()), Collectors.toSet())));
        Map<String, List<WorkspaceDoris>> pathsByHost = paths.findAll().stream()
                .collect(Collectors.groupingBy(WorkspaceDoris::getDorisHostId));
        return new Desired(version, hosts.findAll().stream().map(h -> new HostState(h,
                pathsByHost.getOrDefault(h.getId(), List.of()).stream()
                        .map(p -> new DesiredSa(p.getId(), p.getUsername(), cipher.decrypt(p.getPasswordEnc()),
                                grantsBySa.getOrDefault(p.getId(), Set.of())))
                        .toList()))
                .toList());
    }

    /**
     * Records an apply run of {@code version}. Pending events up to that version are done when it fully
     * succeeded; otherwise they stay pending and count a retry.
     */
    public DorisSyncState recordApply(long version, String error, int drift) {
        DorisSyncState state = lockState();
        state.applied(version, error, drift);
        outbox.findByStatusOrderById(DorisApplyOutbox.PENDING).stream()
                .filter(e -> e.getVersion() <= version)
                .forEach(e -> {
                    if (error == null) {
                        e.done();
                    } else {
                        e.failed();
                    }
                });
        return state;
    }

    @Transactional(readOnly = true)
    public DorisSyncState state() {
        return states.findById(clusterName).orElseGet(() -> new DorisSyncState(clusterName));
    }

    private DorisSyncState lockState() {
        return states.findForUpdate(clusterName).orElseGet(() -> states.save(new DorisSyncState(clusterName)));
    }

    private static void validate(Snapshot s) {
        Map<String, Snapshot.Host> hostById = s.hosts().stream()
                .collect(Collectors.toMap(Snapshot.Host::id, Function.identity()));
        for (Snapshot.Host h : s.hosts()) {
            require(INSTANCE_NAME.matcher(h.instanceName()).matches(), "instance_name 不合法：" + h.instanceName());
            require(HOSTNAME.matcher(h.feHost()).matches(), "fe_host 不合法：" + h.feHost());
            require(ENV_NAME.matcher(h.secretRef()).matches(), "secret_ref 不合法：" + h.secretRef());
        }
        Set<String> saIds = s.paths().stream().map(Snapshot.Path::saId).collect(Collectors.toSet());
        for (Snapshot.Path p : s.paths()) {
            require(hostById.containsKey(p.hostId()), p.username() + " 指向不存在的 host");
            require(USERNAME.matcher(p.username()).matches(), "username 不合法：" + p.username());
            require(PASSWORD.matcher(p.password()).matches(), p.username() + " 的密碼格式不合法");
        }
        for (Snapshot.Grant g : s.grants()) {
            require(saIds.contains(g.saId()), "授權指向不存在的 sa_id " + g.saId());
            require(IDENTIFIER.matcher(g.catalog()).matches() && IDENTIFIER.matcher(g.database()).matches()
                    && (g.table().equals("*") || IDENTIFIER.matcher(g.table()).matches()),
                    "授權對象不合法：" + g.catalog() + "." + g.database() + "." + g.table());
            require(PRIVILEGES.contains(g.privilege()), "不支援的權限 " + g.privilege());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}

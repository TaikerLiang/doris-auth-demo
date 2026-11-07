package poc.doris.console.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.console.domain.ClusterRepository;
import poc.doris.console.domain.DorisClusterSync;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisHostRepository;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.domain.WorkspaceDorisRepository;

/**
 * Sync segment 1: sends each fab's full desired state to its Cluster API, and reads back what was applied.
 * HTTP calls run outside transactions; Console DB reads and writes around them each get their own.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final ClusterRepository clusters;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaGrantRepository grants;
    private final DorisSyncOutboxRepository outbox;
    private final DorisClusterSyncRepository syncs;
    private final CredentialCipher cipher;
    private final ClusterClient client;
    private final TransactionTemplate tx;

    public SyncService(ClusterRepository clusters, DorisHostRepository hosts, WorkspaceDorisRepository paths,
            DorisSaGrantRepository grants, DorisSyncOutboxRepository outbox, DorisClusterSyncRepository syncs,
            CredentialCipher cipher, ClusterClient client, TransactionTemplate tx) {
        this.clusters = clusters;
        this.hosts = hosts;
        this.paths = paths;
        this.grants = grants;
        this.outbox = outbox;
        this.syncs = syncs;
        this.cipher = cipher;
        this.client = client;
        this.tx = tx;
    }

    /**
     * outbox-relay: for every fab with due PENDING events, sends one snapshot of its current state. The state
     * is full, so the newest version covers every older event. Returns how many fabs were sent.
     */
    public int relay() {
        LocalDateTime now = LocalDateTime.now();
        Set<String> due = outbox.findByStatusOrderById(DorisSyncOutbox.PENDING).stream()
                .filter(e -> e.getNextAttemptAt() == null || !e.getNextAttemptAt().isAfter(now))
                .map(DorisSyncOutbox::getClusterName)
                .filter(c -> client.endpoint(c) != null)
                .collect(Collectors.toCollection(TreeSet::new));
        int sent = 0;
        for (String cluster : due) {
            Snapshot snapshot = tx.execute(status -> snapshot(cluster));
            try {
                ClusterClient.PutResult result = client.putState(snapshot);
                if (!result.accepted()) {
                    // The fab already has this version (manual trigger or periodic sync): ask it to apply again
                    client.trigger(cluster);
                }
                tx.executeWithoutResult(status -> markSent(cluster, snapshot.version()));
                log.info("relay {} v{} sent (accepted={})", cluster, snapshot.version(), result.accepted());
                sent++;
            } catch (RuntimeException e) {
                tx.executeWithoutResult(status -> markFailed(cluster, snapshot.version()));
                log.warn("relay {} v{} failed: {}", cluster, snapshot.version(), e.getMessage());
            }
        }
        return sent;
    }

    /** periodic-sync and the manual trigger: queue a resend of a fab's current version (no bump). */
    public void enqueue(String cluster) {
        tx.executeWithoutResult(status -> {
            DorisClusterSync sync = syncs.findById(cluster)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到 cluster " + cluster));
            outbox.save(new DorisSyncOutbox(cluster, sync.getDesiredVersion()));
        });
    }

    public int enqueueAll() {
        List<String> names = clusters.findAllByOrderByLogicClusterName().stream()
                .map(c -> c.getLogicClusterName()).toList();
        names.forEach(this::enqueue);
        return names.size();
    }

    /** status-poll: copies each fab's applied version, last error and drift into doris_cluster_sync. */
    public int pollStatus() {
        int polled = 0;
        for (DorisClusterSync sync : syncs.findAllByOrderByClusterName()) {
            String cluster = sync.getClusterName();
            if (client.endpoint(cluster) == null) {
                continue;
            }
            try {
                ClusterClient.Status s = client.status(cluster);
                // Row lock: the update writes every column, so without it a version bump committed by a
                // permission change in between would be overwritten with the old desired_version
                tx.executeWithoutResult(status -> syncs.findForUpdate(cluster)
                        .ifPresent(row -> row.reported(s.appliedVersion(), s.lastError(), s.lastDrift())));
                polled++;
            } catch (RuntimeException e) {
                tx.executeWithoutResult(status -> syncs.findForUpdate(cluster).ifPresent(row -> row.reported(
                        row.getAppliedVersion(), "無法連線 Cluster API：" + e.getMessage(), row.getLastDrift())));
                log.warn("status-poll {} failed: {}", cluster, e.getMessage());
            }
        }
        return polled;
    }

    /** The fab's state and its desired_version, read in one transaction so they match. */
    private Snapshot snapshot(String cluster) {
        long version = syncs.findById(cluster).map(DorisClusterSync::getDesiredVersion).orElse(0L);
        List<WorkspaceDoris> fabPaths = paths.findByClusterName(cluster);
        return new Snapshot(cluster, version,
                hosts.findByClusterName(cluster).stream().map(h -> new Snapshot.Host(h.getId(), h.getInstanceName(),
                        h.getFeHost(), h.getQueryPort(), h.getHttpPort(), h.getSecretRef())).toList(),
                fabPaths.stream().map(p -> new Snapshot.Path(p.getId(), p.getWorkspaceName(), p.getDorisHostId(),
                        p.getUsername(), cipher.decrypt(p.getPasswordEnc()))).toList(),
                fabPaths.stream()
                        .flatMap(p -> grants
                                .findByWorkspaceDorisIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(
                                        p.getId())
                                .stream())
                        .map(g -> new Snapshot.Grant(g.getWorkspaceDorisId(), g.getCatalogName(),
                                g.getDatabaseName(), g.getTableName(), g.getPrivilege()))
                        .toList());
    }

    private void markSent(String cluster, long version) {
        pending(cluster).stream().filter(e -> e.getDesiredVersion() <= version).forEach(DorisSyncOutbox::sent);
    }

    /** Exponential backoff from 5s, capped at 5 minutes. */
    private void markFailed(String cluster, long version) {
        for (DorisSyncOutbox e : pending(cluster)) {
            if (e.getDesiredVersion() <= version) {
                Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(e.getRetryCount(), 6));
                e.failed(LocalDateTime.now().plus(wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait));
            }
        }
    }

    private List<DorisSyncOutbox> pending(String cluster) {
        return outbox.findByStatusOrderById(DorisSyncOutbox.PENDING).stream()
                .filter(e -> e.getClusterName().equals(cluster)).toList();
    }

    /** For the sync page: per fab, its pending events in id order. */
    public Map<String, List<DorisSyncOutbox>> pendingByCluster() {
        return outbox.findByStatusOrderById(DorisSyncOutbox.PENDING).stream()
                .collect(Collectors.groupingBy(DorisSyncOutbox::getClusterName));
    }
}

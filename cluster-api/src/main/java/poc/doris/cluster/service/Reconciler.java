package poc.doris.cluster.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import poc.doris.cluster.domain.DorisHost;
import poc.doris.cluster.domain.DorisManagedUser;
import poc.doris.cluster.domain.DorisManagedUserRepository;
import poc.doris.cluster.service.CredentialStore.Cred;
import poc.doris.cluster.service.ReconcilePlanner.DesiredSa;
import poc.doris.cluster.service.ReconcilePlanner.Kind;
import poc.doris.cluster.service.ReconcilePlanner.ManagedSa;
import poc.doris.cluster.service.ReconcilePlanner.SaPlan;
import poc.doris.cluster.service.ReconcilePlanner.Sql;
import poc.doris.cluster.service.StateService.HostState;

/**
 * Sync segment 2 for one host: reads SHOW ALL GRANTS, applies the planner's statements, then rebuilds the
 * host's credentials in Redis. A failing statement is recorded and the rest still run, so one bad grant
 * never blocks other SAs.
 */
@Component
public class Reconciler {

    /** {@code executed}: statements that succeeded. {@code errors}: empty when the host fully matches. */
    public record Result(int executed, List<String> errors) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final DorisAdmin doris;
    private final DorisManagedUserRepository managedUsers;
    private final CredentialStore credentials;

    public Reconciler(DorisAdmin doris, DorisManagedUserRepository managedUsers, CredentialStore credentials) {
        this.doris = doris;
        this.managedUsers = managedUsers;
        this.credentials = credentials;
    }

    public Result reconcile(HostState state) {
        DorisHost host = state.host();
        String instance = host.getInstanceName();
        List<String> errors = new ArrayList<>();
        int executed = 0;
        Map<String, DesiredSa> desired = state.sas().stream()
                .collect(Collectors.toMap(DesiredSa::username, Function.identity()));
        Map<String, Cred> creds = new HashMap<>();
        Set<String> keep = new HashSet<>(); // CREATE/ALTER USER failed: Doris still has the old password, if any
        try (Connection conn = doris.connect(host)) {
            Map<String, ManagedSa> managed = managedUsers.findByDorisHostId(host.getId()).stream()
                    .collect(Collectors.toMap(DorisManagedUser::getUsername,
                            m -> new ManagedSa(m.getSaId(), m.getPasswordHash())));
            for (SaPlan plan : ReconcilePlanner.plan(state.sas(), doris.readGrants(conn), managed)) {
                if (plan.conflict()) {
                    errors.add(instance + ": " + plan.username() + " 已存在於 Doris 但不是 Cluster 建立的，不接管");
                    continue;
                }
                DesiredSa sa = desired.get(plan.username());
                boolean saOk = true;
                boolean userOk = true;
                for (Sql sql : plan.statements()) {
                    if (sql.kind() == Kind.CREATE_USER) {
                        // Recorded first: if CREATE USER then fails, the next run creates it instead of
                        // treating it as someone else's account
                        remember(host.getId(), sa);
                    }
                    try {
                        doris.execute(conn, sql.sql());
                        executed++;
                        log.info("{}: {}", instance, sql.display());
                        if (sql.kind() == Kind.ALTER_USER) {
                            remember(host.getId(), sa);
                        }
                    } catch (SQLException e) {
                        saOk = false;
                        errors.add(instance + ": " + sql.display() + " → " + e.getMessage());
                        log.warn("{}: {} failed: {}", instance, sql.display(), e.getMessage());
                        if (sql.kind() == Kind.CREATE_USER || sql.kind() == Kind.ALTER_USER) {
                            userOk = false;
                        }
                        if (sql.kind() == Kind.CREATE_USER) {
                            break; // the grants after it would only fail too
                        }
                    }
                }
                if (plan.forget() && saOk) {
                    managedUsers.findByDorisHostIdAndUsername(host.getId(), plan.username())
                            .ifPresent(managedUsers::delete);
                }
                if (sa != null && userOk) {
                    creds.put(sa.saId(), new Cred(host.getFeHost(), host.getQueryPort(), sa.username(),
                            sa.password()));
                } else if (sa != null) {
                    keep.add(sa.saId());
                }
            }
        } catch (SQLException | RuntimeException e) {
            // Could not connect or read grants: leave Redis as it is
            errors.add(instance + ": " + e.getMessage());
            log.warn("{}: reconcile failed: {}", instance, e.getMessage());
            return new Result(executed, errors);
        }
        try {
            credentials.publish(instance, creds, keep);
        } catch (RuntimeException e) {
            errors.add(instance + ": Redis " + e.getMessage());
            log.warn("{}: publishing credentials failed: {}", instance, e.getMessage());
        }
        return new Result(executed, errors);
    }

    private void remember(String hostId, DesiredSa sa) {
        String hash = ReconcilePlanner.hash(sa.password());
        DorisManagedUser user = managedUsers.findByDorisHostIdAndUsername(hostId, sa.username())
                .orElseGet(() -> new DorisManagedUser(hostId, sa.username(), sa.saId(), hash));
        user.update(sa.saId(), hash);
        managedUsers.save(user);
    }
}

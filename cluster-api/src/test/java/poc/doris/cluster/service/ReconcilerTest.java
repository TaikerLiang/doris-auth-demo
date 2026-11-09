package poc.doris.cluster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import poc.doris.cluster.domain.DorisHost;
import poc.doris.cluster.domain.DorisManagedUserRepository;
import poc.doris.cluster.service.ReconcilePlanner.DesiredSa;
import poc.doris.cluster.service.ReconcilePlanner.Sql;
import poc.doris.cluster.service.StateService.HostState;

class ReconcilerTest {

    static final DorisHost HOST = new DorisHost("h1", "doris-fab-a", "fe-a", 9030, 8030, "DORIS_FAB_A_ADMIN");
    static final DorisGrant ORDERS = new DorisGrant("internal", "olist", "orders", "SELECT_PRIV");
    static final HostState STATE = new HostState(HOST,
            List.of(new DesiredSa("1", "sa_a", "pw00000000000000", Set.of(ORDERS))));

    final DorisManagedUserRepository managedUsers = mock(DorisManagedUserRepository.class);
    final CredentialStore credentials = mock(CredentialStore.class);

    @Test
    void withoutAClientEveryHostFailsAndRedisIsLeftAlone() {
        Reconciler reconciler = new Reconciler(new UnconfiguredDorisClient(), managedUsers, credentials);

        Reconciler.Result result = reconciler.reconcile(STATE);

        assertThat(result.executed()).isZero();
        assertThat(result.errors()).singleElement().asString()
                .startsWith("doris-fab-a: ").contains("DorisClient");
        verify(credentials, never()).publish(anyString(), anyMap(), anySet());
    }

    @Test
    void appliesPlannedStatementsThroughTheSession() {
        List<Sql> applied = new ArrayList<>();
        DorisClient client = host -> new DorisClient.Session() {
            @Override
            public Map<String, Set<DorisGrant>> readGrants() {
                return Map.of();
            }

            @Override
            public void apply(Sql statement) {
                applied.add(statement);
            }

            @Override
            public void close() {
            }
        };

        Reconciler.Result result = new Reconciler(client, managedUsers, credentials).reconcile(STATE);

        assertThat(result.errors()).isEmpty();
        assertThat(result.executed()).isEqualTo(2);
        assertThat(applied).extracting(Sql::display).containsExactly(
                "CREATE USER 'sa_a'@'%' IDENTIFIED BY '***'",
                "GRANT SELECT_PRIV ON internal.olist.orders TO 'sa_a'@'%'");
        verify(credentials).publish(eq("doris-fab-a"), any(), any());
    }
}

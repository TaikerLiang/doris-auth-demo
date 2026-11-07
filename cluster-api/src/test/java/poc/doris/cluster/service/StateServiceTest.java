package poc.doris.cluster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.cluster.domain.DorisApplyOutbox;
import poc.doris.cluster.domain.DorisApplyOutboxRepository;
import poc.doris.cluster.domain.WorkspaceDorisRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StateServiceTest {

    static final String PASSWORD = "Abcdefghijklmnop1234567890ABCDEF";

    @MockitoBean RedissonClient redis;
    @Autowired StateService state;
    @Autowired DorisApplyOutboxRepository outbox;
    @Autowired WorkspaceDorisRepository paths;

    @Test
    void acceptsNewerSnapshotAndQueuesApply() {
        assertThat(state.receive(snapshot("fab-a", 1, "orders"))).isTrue();

        assertThat(state.state().getReceivedVersion()).isEqualTo(1);
        assertThat(outbox.findByStatusOrderById(DorisApplyOutbox.PENDING)).extracting(DorisApplyOutbox::getVersion)
                .containsExactly(1L);
        assertThat(paths.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getPasswordEnc()).isNotEqualTo(PASSWORD));
        assertThat(state.loadDesired().hosts()).singleElement().satisfies(h -> assertThat(h.sas()).singleElement()
                .satisfies(sa -> {
                    assertThat(sa.password()).isEqualTo(PASSWORD);
                    assertThat(sa.grants()).containsExactly(
                            new DorisGrant("internal", "olist", "orders", "SELECT_PRIV"));
                }));
    }

    @Test
    void ignoresSnapshotThatIsNotNewer() {
        state.receive(snapshot("fab-a", 2, "orders"));

        assertThat(state.receive(snapshot("fab-a", 2, "customers"))).isFalse();
        assertThat(state.receive(snapshot("fab-a", 1, "customers"))).isFalse();
        assertThat(state.loadDesired().hosts().get(0).sas().get(0).grants())
                .extracting(DorisGrant::table).containsExactly("orders");
    }

    @Test
    void rejectsOtherFabAndUnsafeValues() {
        assertStatus(() -> state.receive(snapshot("fab-b", 1, "orders")));
        assertStatus(() -> state.receive(snapshot("fab-a", 1, "orders`; DROP")));
        assertThat(state.state().getReceivedVersion()).isZero();
    }

    @Test
    void failedApplyKeepsEventPendingAndAppliedVersion() {
        state.receive(snapshot("fab-a", 1, "orders"));
        state.recordApply(1, null, 0);
        state.receive(snapshot("fab-a", 2, "nope"));

        state.recordApply(2, "doris-fab-a: table:nope does not exist", 0);

        assertThat(state.state().getAppliedVersion()).isEqualTo(1);
        assertThat(state.state().getLastError()).contains("nope");
        assertThat(outbox.findByStatusOrderById(DorisApplyOutbox.PENDING)).extracting(DorisApplyOutbox::getVersion)
                .containsExactly(2L);
    }

    private static Snapshot snapshot(String cluster, long version, String table) {
        return new Snapshot(cluster, version,
                List.of(new Snapshot.Host("h1", "doris-fab-a", "fe-a", 9030, 8030, "DORIS_FAB_A_ADMIN")),
                List.of(new Snapshot.Path("sa1", "workspace-a", "h1", "sa_workspace_a", PASSWORD)),
                List.of(new Snapshot.Grant("sa1", "internal", "olist", table, "SELECT_PRIV")));
    }

    private static void assertStatus(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}

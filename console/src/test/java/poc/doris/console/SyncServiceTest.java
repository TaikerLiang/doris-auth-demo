package poc.doris.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.service.ClusterClient;
import poc.doris.console.service.PermissionService;
import poc.doris.console.service.Snapshot;
import poc.doris.console.service.SyncService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SyncServiceTest {

    @MockitoBean RedissonClient redis;
    @MockitoBean ClusterClient client;
    @Autowired SyncService sync;
    @Autowired PermissionService permissions;
    @Autowired DorisSyncOutboxRepository outbox;
    @Autowired DorisClusterSyncRepository syncs;

    @BeforeEach
    void fabAHasAnEndpoint() {
        when(client.endpoint("fab-a")).thenReturn("http://cluster-api-a:8080");
    }

    @Test
    void sendsOnlyTheNewestVersionAndMarksEveryOlderEventSent() {
        changeFabAThreeTimes();
        when(client.putState(any())).thenReturn(new ClusterClient.PutResult(true, 3));

        assertThat(sync.relay()).isEqualTo(1);

        ArgumentCaptor<Snapshot> sent = ArgumentCaptor.forClass(Snapshot.class);
        verify(client, times(1)).putState(sent.capture());
        Snapshot s = sent.getValue();
        assertThat(s.cluster()).isEqualTo("fab-a");
        assertThat(s.version()).isEqualTo(3);
        assertThat(s.hosts()).extracting(Snapshot.Host::instanceName).containsExactly("doris-fab-a");
        assertThat(s.paths()).singleElement().satisfies(p -> {
            assertThat(p.username()).isEqualTo("sa_workspace_a");
            assertThat(p.password()).hasSize(32); // decrypted for transit
        });
        assertThat(s.grants()).extracting(Snapshot.Grant::table).containsExactly("customers", "orders");
        assertThat(outbox.findAll()).extracting(DorisSyncOutbox::getStatus).containsOnly(DorisSyncOutbox.SENT);
    }

    @Test
    void failedSendBacksOffInsteadOfRetryingRightAway() {
        changeFabAThreeTimes();
        when(client.putState(any())).thenThrow(new IllegalStateException("connection refused"));

        assertThat(sync.relay()).isZero();
        assertThat(outbox.findAll()).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(DorisSyncOutbox.PENDING);
            assertThat(e.getRetryCount()).isEqualTo(1);
            assertThat(e.getNextAttemptAt()).isAfter(LocalDateTime.now());
        });

        sync.relay();
        verify(client, times(1)).putState(any());
    }

    @Test
    void versionTheFabAlreadyHasAsksItToApplyAgain() {
        changeFabAThreeTimes();
        when(client.putState(any())).thenReturn(new ClusterClient.PutResult(true, 3));
        sync.relay();

        sync.enqueue("fab-a"); // manual trigger: same version again
        when(client.putState(any())).thenReturn(new ClusterClient.PutResult(false, 3));
        sync.relay();

        verify(client).trigger("fab-a");
    }

    @Test
    void fabWithoutEndpointIsNeverSent() {
        permissions.openPath("workspace-b", "fab-b", "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0b01");

        assertThat(sync.relay()).isZero();
        verify(client, never()).putState(any());
        assertThat(outbox.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getStatus()).isEqualTo(DorisSyncOutbox.PENDING));
    }

    @Test
    void pollCopiesWhatTheFabApplied() {
        when(client.status("fab-a")).thenReturn(new ClusterClient.Status("fab-a", 3, 2L, "table:nope does not exist", 1));

        assertThat(sync.pollStatus()).isEqualTo(1);

        assertThat(syncs.findById("fab-a").orElseThrow()).satisfies(s -> {
            assertThat(s.getAppliedVersion()).isEqualTo(2);
            assertThat(s.getLastError()).contains("nope");
            assertThat(s.getLastDrift()).isEqualTo(1);
        });
    }

    private void changeFabAThreeTimes() {
        permissions.openPath("workspace-a", "fab-a", "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0a01");
        permissions.addGrant("workspace-a", "fab-a", "internal", "olist", "orders", "SELECT_PRIV");
        permissions.addGrant("workspace-a", "fab-a", "internal", "olist", "customers", "SELECT_PRIV");
    }
}

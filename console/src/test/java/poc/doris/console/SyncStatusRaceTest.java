package poc.doris.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.service.ClusterClient;
import poc.doris.console.service.SyncService;

/** Not @Transactional: the two sides must really commit, in separate threads. */
@SpringBootTest
@ActiveProfiles("test")
class SyncStatusRaceTest {

    @MockitoBean RedissonClient redis;
    @MockitoBean ClusterClient client;
    @Autowired SyncService sync;
    @Autowired DorisClusterSyncRepository syncs;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void statusPollDoesNotOverwriteAVersionBumpCommittedMeanwhile() throws Exception {
        when(client.endpoint("fab-a")).thenReturn("http://cluster-api-a:8080");
        when(client.status("fab-a")).thenReturn(new ClusterClient.Status("fab-a", 0, 0L, null, 0));
        CountDownLatch locked = new CountDownLatch(1);

        // A permission change holding the row lock while status-poll runs
        CompletableFuture<Void> change = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            syncs.findForUpdate("fab-a").orElseThrow().nextDesiredVersion();
            locked.countDown();
            sleep(300);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        sync.pollStatus();
        change.get(5, TimeUnit.SECONDS);

        assertThat(syncs.findById("fab-a").orElseThrow().getDesiredVersion()).isEqualTo(1);
    }

    @AfterEach
    void resetFabA() {
        jdbc.update("update doris_cluster_sync set desired_version = 0, applied_version = null, last_error = null,"
                + " last_drift = 0 where cluster_name = 'fab-a'");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

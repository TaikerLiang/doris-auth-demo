package poc.doris.console.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class SchedulerTest {

    final RedissonClient redis = mock(RedissonClient.class);
    final RLock lock = mock(RLock.class);
    final AtomicInteger runs = new AtomicInteger();

    @Test
    void skipsRunWhenAnotherReplicaHoldsTheLock() {
        when(redis.getLock("k")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        job(false).execute("job");

        assertThat(runs).hasValue(0);
    }

    @Test
    void runsAndReleasesTheLockEvenWhenTheJobFails() {
        when(redis.getLock("k")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        job(true).execute("job");

        assertThat(runs).hasValue(1);
        verify(lock).unlock();
    }

    @Test
    void registersOnlyEnabledJobsWithTheirZone() {
        SchedulerProperties properties = new SchedulerProperties("Asia/Taipei", Map.of(
                "on", new SchedulerProperties.Job(true, "*/5 * * * * *", null),
                "off", new SchedulerProperties.Job(false, "*/5 * * * * *", null),
                "utc", new SchedulerProperties.Job(true, "0 0 3 * * *", "UTC")));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new SchedulerConfig(Map.of("on", job(false), "off", job(false), "utc", job(false), "no-config", job(false)),
                properties).configureTasks(registrar);

        assertThat(registrar.getCronTaskList()).extracting(CronTask::getExpression)
                .containsExactlyInAnyOrder("*/5 * * * * *", "0 0 3 * * *");
    }

    private BaseScheduler job(boolean fail) {
        return new BaseScheduler(redis) {
            @Override
            protected String lockKey() {
                return "k";
            }

            @Override
            protected int run() {
                runs.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("boom");
                }
                return 1;
            }
        };
    }
}

package poc.doris.console.scheduler;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import poc.doris.console.service.SyncService;

/** Queues a resend for every fab (decision 9), so a missed send or a wiped Cluster DB catches up. */
@Component("periodic-sync")
class PeriodicSyncJob extends BaseScheduler {

    private final SyncService sync;

    PeriodicSyncJob(RedissonClient redis, SyncService sync) {
        super(redis);
        this.sync = sync;
    }

    @Override
    protected String lockKey() {
        return "scheduler:console:periodic-sync";
    }

    @Override
    protected int run() {
        return sync.enqueueAll();
    }
}

package poc.doris.console.scheduler;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import poc.doris.console.service.SyncService;

/** Sync segment 1: sends each fab with pending outbox events its current snapshot. */
@Component("outbox-relay")
class OutboxRelayJob extends BaseScheduler {

    private final SyncService sync;

    OutboxRelayJob(RedissonClient redis, SyncService sync) {
        super(redis);
        this.sync = sync;
    }

    @Override
    protected String lockKey() {
        return "scheduler:console:outbox-relay";
    }

    @Override
    protected int run() {
        return sync.relay();
    }
}

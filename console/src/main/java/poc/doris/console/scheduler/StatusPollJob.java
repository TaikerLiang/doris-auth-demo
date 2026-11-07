package poc.doris.console.scheduler;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import poc.doris.console.service.SyncService;

/** Copies each fab's applied version, last error and drift from its Cluster API. */
@Component("status-poll")
class StatusPollJob extends BaseScheduler {

    private final SyncService sync;

    StatusPollJob(RedissonClient redis, SyncService sync) {
        super(redis);
        this.sync = sync;
    }

    @Override
    protected String lockKey() {
        return "scheduler:console:status-poll";
    }

    @Override
    protected int run() {
        return sync.pollStatus();
    }
}

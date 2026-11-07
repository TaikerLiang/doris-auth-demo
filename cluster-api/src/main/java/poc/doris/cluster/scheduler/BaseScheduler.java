package poc.doris.cluster.scheduler;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every scheduled job extends this and only implements {@link #run()}. Each run first takes the job's Redis
 * lock, so across replicas only one runs at a time; a run that cannot get the lock is skipped and logged.
 * The lock has no lease time: Redisson's watchdog renews it while the job runs, and it expires about 30s
 * after the holder dies. See https://blog.taiker.space/spring-scheduler-solution/
 */
public abstract class BaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(BaseScheduler.class);

    private final RedissonClient redis;

    protected BaseScheduler(RedissonClient redis) {
        this.redis = redis;
    }

    /** Redis key of the lock. Jobs that must never overlap share a key. */
    protected abstract String lockKey();

    /** One run; returns how many items it processed, for the log. */
    protected abstract int run();

    public final void execute(String job) {
        RLock lock = redis.getLock(lockKey());
        if (!lock.tryLock()) {
            log.info("job={} skipped: lock {} is held", job, lockKey());
            return;
        }
        long start = System.currentTimeMillis();
        try {
            int processed = run();
            long ms = System.currentTimeMillis() - start;
            if (processed > 0) {
                log.info("job={} ok processed={} durationMs={}", job, processed, ms);
            } else {
                log.debug("job={} ok processed=0 durationMs={}", job, ms);
            }
        } catch (RuntimeException e) {
            log.warn("job={} failed durationMs={}", job, System.currentTimeMillis() - start, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

package poc.doris.cluster.scheduler;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import poc.doris.cluster.service.ApplyService;

/** Sync segment 2: runs pending doris_apply_outbox events (new snapshots, manual triggers, retries). */
@Component("doris-apply")
class DorisApplyJob extends BaseScheduler {

    private final ApplyService apply;
    private final String clusterName;

    DorisApplyJob(RedissonClient redis, ApplyService apply, @Value("${cluster.name}") String clusterName) {
        super(redis);
        this.apply = apply;
        this.clusterName = clusterName;
    }

    /** Shared with doris-reconcile: two runs on one Doris could each act on a stale SHOW ALL GRANTS. */
    @Override
    protected String lockKey() {
        return "scheduler:" + clusterName + ":doris";
    }

    @Override
    protected int run() {
        return apply.drain();
    }
}

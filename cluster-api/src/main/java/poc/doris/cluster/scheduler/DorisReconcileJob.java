package poc.doris.cluster.scheduler;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import poc.doris.cluster.service.ApplyService;

/** Applies the current state even without a new version, so changes made by hand on Doris are reverted. */
@Component("doris-reconcile")
class DorisReconcileJob extends BaseScheduler {

    private final ApplyService apply;
    private final String clusterName;

    DorisReconcileJob(RedissonClient redis, ApplyService apply, @Value("${cluster.name}") String clusterName) {
        super(redis);
        this.apply = apply;
        this.clusterName = clusterName;
    }

    @Override
    protected String lockKey() {
        return "scheduler:" + clusterName + ":doris";
    }

    @Override
    protected int run() {
        return apply.reconcile();
    }
}

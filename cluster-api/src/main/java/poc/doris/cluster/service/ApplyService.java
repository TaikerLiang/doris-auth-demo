package poc.doris.cluster.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import poc.doris.cluster.domain.DorisSyncState;
import poc.doris.cluster.service.StateService.Desired;
import poc.doris.cluster.service.StateService.HostState;

/**
 * Drains doris_apply_outbox: applies the Cluster DB state to every host of this fab and records the result.
 * Called by POST /doris/trigger for now; the doris-apply / doris-reconcile jobs (#9) will call it on a schedule.
 */
@Service
public class ApplyService {

    private final StateService state;
    private final Reconciler reconciler;

    public ApplyService(StateService state, Reconciler reconciler) {
        this.state = state;
        this.reconciler = reconciler;
    }

    /** Queues an apply of the current state and runs it now. */
    public DorisSyncState trigger() {
        state.enqueueApply();
        return drain();
    }

    /**
     * Synchronized because two runs on one Doris could read SHOW ALL GRANTS before each other's changes;
     * across replicas the #9 lock takes over.
     */
    public synchronized DorisSyncState drain() {
        if (!state.hasPendingApply()) {
            return state.state();
        }
        DorisSyncState before = state.state();
        Desired desired = state.loadDesired();
        int executed = 0;
        List<String> errors = new ArrayList<>();
        for (HostState host : desired.hosts()) {
            Reconciler.Result result = reconciler.reconcile(host);
            executed += result.executed();
            errors.addAll(result.errors());
        }
        // Statements needed for a version that was already applied mean Doris drifted from it
        boolean alreadyApplied = Objects.equals(before.getAppliedVersion(), desired.version());
        return state.recordApply(desired.version(), errors.isEmpty() ? null : String.join("\n", errors),
                alreadyApplied ? executed : 0);
    }
}

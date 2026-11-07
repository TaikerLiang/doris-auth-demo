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
 * Called by the doris-apply and doris-reconcile jobs.
 */
@Service
public class ApplyService {

    private final StateService state;
    private final Reconciler reconciler;

    public ApplyService(StateService state, Reconciler reconciler) {
        this.state = state;
        this.reconciler = reconciler;
    }

    /** doris-reconcile: applies the current state even if no new version arrived, which repairs drift. */
    public int reconcile() {
        state.enqueueApply();
        return drain();
    }

    /**
     * doris-apply: runs the pending apply events, if any. Returns how many statements were executed.
     * Callers hold the fab's scheduler lock, so only one run touches this fab's Doris at a time.
     */
    public int drain() {
        if (!state.hasPendingApply()) {
            return 0;
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
        state.recordApply(desired.version(), errors.isEmpty() ? null : String.join("\n", errors),
                alreadyApplied ? executed : 0);
        return executed;
    }
}

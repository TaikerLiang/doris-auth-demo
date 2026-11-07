package poc.doris.cluster.web;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import poc.doris.cluster.domain.DorisSyncState;
import poc.doris.cluster.service.Snapshot;
import poc.doris.cluster.service.StateService;

/** What Console calls: push the desired state, trigger an apply, read the sync result. */
@RestController
@RequestMapping("/doris")
class StateController {

    record Status(String cluster, long receivedVersion, Long appliedVersion, String lastError, int lastDrift,
            LocalDateTime updatedAt) {

        static Status of(DorisSyncState s) {
            return new Status(s.getClusterName(), s.getReceivedVersion(), s.getAppliedVersion(), s.getLastError(),
                    s.getLastDrift(), s.getUpdatedAt());
        }
    }

    private final StateService state;

    StateController(StateService state) {
        this.state = state;
    }

    @PutMapping("/state")
    Map<String, Object> receive(@RequestBody Snapshot snapshot) {
        boolean accepted = state.receive(snapshot);
        return Map.of("accepted", accepted, "receivedVersion", state.state().getReceivedVersion());
    }

    /** Only queues an apply event; the doris-apply job runs it within seconds (see GET /doris/status). */
    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Status trigger() {
        state.enqueueApply();
        return Status.of(state.state());
    }

    @GetMapping("/status")
    Status status() {
        return Status.of(state.state());
    }
}

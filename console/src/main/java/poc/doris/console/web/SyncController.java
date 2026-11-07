package poc.doris.console.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import poc.doris.console.domain.DorisClusterSync;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.service.ClusterClient;
import poc.doris.console.service.SyncService;

@Controller
class SyncController {

    /** One fab on the sync page. {@code behind}: Doris has not applied the desired version yet. */
    record FabSync(DorisClusterSync sync, String endpoint, int pending, LocalDateTime oldestPending, int maxRetry,
            LocalDateTime nextAttempt, boolean behind) {
    }

    private final DorisClusterSyncRepository syncs;
    private final DorisSyncOutboxRepository outbox;
    private final SyncService sync;
    private final ClusterClient client;

    SyncController(DorisClusterSyncRepository syncs, DorisSyncOutboxRepository outbox, SyncService sync,
            ClusterClient client) {
        this.syncs = syncs;
        this.outbox = outbox;
        this.sync = sync;
        this.client = client;
    }

    @GetMapping("/sync")
    String status(Model model) {
        Map<String, List<DorisSyncOutbox>> pending = sync.pendingByCluster();
        model.addAttribute("fabs", syncs.findAllByOrderByClusterName().stream().map(s -> {
            List<DorisSyncOutbox> events = pending.getOrDefault(s.getClusterName(), List.of());
            return new FabSync(s, client.endpoint(s.getClusterName()), events.size(),
                    events.isEmpty() ? null : events.get(0).getCreatedAt(),
                    events.stream().mapToInt(DorisSyncOutbox::getRetryCount).max().orElse(0),
                    events.stream().map(DorisSyncOutbox::getNextAttemptAt).filter(Objects::nonNull).max(
                            LocalDateTime::compareTo).orElse(null),
                    !Objects.equals(s.getAppliedVersion(), s.getDesiredVersion()));
        }).toList());
        model.addAttribute("events", outbox.findTop50ByOrderByIdDesc());
        return "sync";
    }

    @PostMapping("/sync/{cluster}/trigger")
    String trigger(@PathVariable String cluster, RedirectAttributes flash) {
        sync.enqueue(cluster);
        flash.addFlashAttribute("notice", "已排入 " + cluster + " 的同步，幾秒內會送出");
        return "redirect:/sync";
    }

    /** Same as the button, for scripts: queues a resend of the fab's current version. */
    @PostMapping("/api/sync/{cluster}")
    @ResponseBody
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> triggerApi(@PathVariable String cluster) {
        sync.enqueue(cluster);
        return Map.of("cluster", cluster, "status", "queued");
    }
}

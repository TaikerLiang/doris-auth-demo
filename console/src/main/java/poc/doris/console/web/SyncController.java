package poc.doris.console.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisSyncOutboxRepository;

@Controller
class SyncController {

    private final DorisClusterSyncRepository syncs;
    private final DorisSyncOutboxRepository outbox;

    SyncController(DorisClusterSyncRepository syncs, DorisSyncOutboxRepository outbox) {
        this.syncs = syncs;
        this.outbox = outbox;
    }

    @GetMapping("/sync")
    String status(Model model) {
        model.addAttribute("syncs", syncs.findAllByOrderByClusterName());
        model.addAttribute("events", outbox.findTop50ByOrderByIdDesc());
        return "sync";
    }
}

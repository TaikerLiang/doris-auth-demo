package poc.doris.console.web;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import poc.doris.console.domain.ClusterRepository;
import poc.doris.console.domain.DorisHost;
import poc.doris.console.domain.DorisHostRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.domain.WorkspaceDorisRepository;
import poc.doris.console.service.PermissionService;

@Controller
class HostController {

    private final ClusterRepository clusters;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final PermissionService service;

    HostController(ClusterRepository clusters, DorisHostRepository hosts, WorkspaceDorisRepository paths,
            PermissionService service) {
        this.clusters = clusters;
        this.hosts = hosts;
        this.paths = paths;
        this.service = service;
    }

    @GetMapping("/hosts")
    String list(Model model) {
        Map<String, Long> pathCounts = paths.findAll().stream()
                .collect(Collectors.groupingBy(WorkspaceDoris::getDorisHostId, Collectors.counting()));
        model.addAttribute("hosts", hosts.findAllByOrderByClusterNameAscInstanceNameAsc());
        model.addAttribute("pathCounts", pathCounts);
        model.addAttribute("clusters", clusters.findAllByOrderByLogicClusterName());
        return "hosts";
    }

    @PostMapping("/hosts")
    String register(@RequestParam String clusterName, @RequestParam String instanceName,
            @RequestParam String feHost, @RequestParam int queryPort, @RequestParam int httpPort,
            @RequestParam String secretRef, RedirectAttributes flash) {
        DorisHost host = service.registerHost(clusterName, instanceName.trim(), feHost.trim(), queryPort, httpPort,
                secretRef.trim());
        flash.addFlashAttribute("notice", "已登記 " + host.getInstanceName());
        return "redirect:/hosts";
    }

    @PostMapping("/hosts/{id}/delete")
    String delete(@PathVariable String id, RedirectAttributes flash) {
        service.deleteHost(id);
        flash.addFlashAttribute("notice", "已刪除 Doris host");
        return "redirect:/hosts";
    }
}

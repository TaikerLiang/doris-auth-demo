package poc.doris.console.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import poc.doris.console.domain.ClusterRepository;
import poc.doris.console.domain.DorisHost;
import poc.doris.console.domain.DorisHostRepository;
import poc.doris.console.domain.DorisSaGrant;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.domain.WorkspaceDorisRepository;
import poc.doris.console.domain.WorkspaceRepository;
import poc.doris.console.service.PermissionService;

@Controller
class WorkspaceController {

    /** A workspace in one fab. path/host are null when the workspace has no doris path there. */
    record FabPanel(String clusterName, WorkspaceDoris path, DorisHost host, List<DorisSaGrant> grants,
            List<DorisHost> hostChoices) {
    }

    record WorkspaceRow(String workspaceName, List<FabPanel> fabs) {
    }

    private final ClusterRepository clusters;
    private final WorkspaceRepository workspaces;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaGrantRepository grants;
    private final PermissionService service;

    WorkspaceController(ClusterRepository clusters, WorkspaceRepository workspaces, DorisHostRepository hosts,
            WorkspaceDorisRepository paths, DorisSaGrantRepository grants, PermissionService service) {
        this.clusters = clusters;
        this.workspaces = workspaces;
        this.hosts = hosts;
        this.paths = paths;
        this.grants = grants;
        this.service = service;
    }

    @GetMapping("/")
    String home() {
        return "redirect:/workspaces";
    }

    @GetMapping("/workspaces")
    String list(Model model) {
        List<String> clusterNames = clusterNames();
        model.addAttribute("clusters", clusterNames);
        model.addAttribute("rows", workspaces.findAllByOrderByWorkspaceName().stream()
                .map(w -> new WorkspaceRow(w.getWorkspaceName(), panels(w.getWorkspaceName(), clusterNames)))
                .toList());
        return "workspaces";
    }

    @GetMapping("/workspaces/{workspace}")
    String detail(@PathVariable String workspace, Model model) {
        if (!workspaces.existsById(workspace)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("workspace", workspace);
        model.addAttribute("workspaceNames",
                workspaces.findAllByOrderByWorkspaceName().stream().map(w -> w.getWorkspaceName()).toList());
        model.addAttribute("fabs", panels(workspace, clusterNames()));
        model.addAttribute("privileges", PermissionService.PRIVILEGES.stream().sorted().toList());
        return "workspace";
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/path")
    String openPath(@PathVariable String workspace, @PathVariable String cluster, @RequestParam String hostId,
            RedirectAttributes flash) {
        WorkspaceDoris path = service.openPath(workspace, cluster, hostId);
        flash.addFlashAttribute("notice", cluster + " 已開通 doris path，SA " + path.getUsername() + " 的密碼已加密保存");
        return back(workspace);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/path/delete")
    String clearPath(@PathVariable String workspace, @PathVariable String cluster, RedirectAttributes flash) {
        service.clearPath(workspace, cluster);
        flash.addFlashAttribute("notice", cluster + " 的 doris path 已清空");
        return back(workspace);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/grants")
    String addGrant(@PathVariable String workspace, @PathVariable String cluster, @RequestParam String catalog,
            @RequestParam String database, @RequestParam String table, @RequestParam String privilege,
            RedirectAttributes flash) {
        service.addGrant(workspace, cluster, catalog.trim(), database.trim(), table.trim(), privilege);
        flash.addFlashAttribute("notice", "已在 " + cluster + " 授予 " + privilege + " ON "
                + catalog.trim() + "." + database.trim() + "." + table.trim());
        return back(workspace);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/grants/{grantId}/delete")
    String removeGrant(@PathVariable String workspace, @PathVariable String cluster, @PathVariable long grantId,
            RedirectAttributes flash) {
        service.removeGrant(workspace, cluster, grantId);
        flash.addFlashAttribute("notice", "已在 " + cluster + " 移除授權");
        return back(workspace);
    }

    private List<FabPanel> panels(String workspace, List<String> clusterNames) {
        Map<String, WorkspaceDoris> byCluster = paths.findByWorkspaceName(workspace).stream()
                .collect(Collectors.toMap(WorkspaceDoris::getClusterName, Function.identity()));
        List<DorisHost> allHosts = hosts.findAllByOrderByClusterNameAscInstanceNameAsc();
        return clusterNames.stream().map(cluster -> {
            WorkspaceDoris path = byCluster.get(cluster);
            List<DorisHost> choices = allHosts.stream().filter(h -> h.getClusterName().equals(cluster)).toList();
            if (path == null) {
                return new FabPanel(cluster, null, null, List.of(), choices);
            }
            DorisHost host = allHosts.stream().filter(h -> h.getId().equals(path.getDorisHostId()))
                    .findFirst().orElse(null);
            return new FabPanel(cluster, path, host,
                    grants.findByWorkspaceDorisIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(
                            path.getId()),
                    choices);
        }).toList();
    }

    private List<String> clusterNames() {
        return clusters.findAllByOrderByLogicClusterName().stream().map(c -> c.getLogicClusterName()).toList();
    }

    private static String back(String workspace) {
        return "redirect:/workspaces/" + workspace;
    }
}

package poc.doris.console.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.util.UriComponentsBuilder;
import poc.doris.console.domain.ClusterRepository;
import poc.doris.console.domain.DorisHost;
import poc.doris.console.domain.DorisHostRepository;
import poc.doris.console.domain.DorisSa;
import poc.doris.console.domain.DorisSaGrant;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.DorisSaRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.domain.WorkspaceDorisRepository;
import poc.doris.console.domain.WorkspaceRepository;
import poc.doris.console.service.PermissionService;

@Controller
class WorkspaceController {

    /** A doris path of one workspace in one fab. */
    record FabRow(String clusterName, WorkspaceDoris path, DorisHost host, long saCount, long grantCount) {
    }

    /** One SA on the fab page, with its grants. */
    record SaPanel(DorisSa sa, List<DorisSaGrant> grants) {
    }

    /** An SA of the same workspace that grants can be copied to. */
    record CopyTarget(String saId, String label) {
    }

    /** Shown once at the top of the fab page after a password was generated. */
    record ShownPassword(String username, String password) {
    }

    record WorkspaceRow(String workspaceName, List<String> fabs, long grantCount) {
    }

    private final ClusterRepository clusters;
    private final WorkspaceRepository workspaces;
    private final DorisHostRepository hosts;
    private final WorkspaceDorisRepository paths;
    private final DorisSaRepository sas;
    private final DorisSaGrantRepository grants;
    private final PermissionService service;

    WorkspaceController(ClusterRepository clusters, WorkspaceRepository workspaces, DorisHostRepository hosts,
            WorkspaceDorisRepository paths, DorisSaRepository sas, DorisSaGrantRepository grants,
            PermissionService service) {
        this.clusters = clusters;
        this.workspaces = workspaces;
        this.hosts = hosts;
        this.paths = paths;
        this.sas = sas;
        this.grants = grants;
        this.service = service;
    }

    @GetMapping("/")
    String home() {
        return "redirect:/workspaces";
    }

    /** {@code go} comes from the workspace search in the top bar: an exact name opens that workspace. */
    @GetMapping("/workspaces")
    String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String fab,
            @RequestParam(required = false) String go, Model model) {
        if (go != null) {
            String name = go.trim();
            return workspaces.existsById(name) ? back(name)
                    : "redirect:" + UriComponentsBuilder.fromPath("/workspaces").queryParam("q", name).toUriString();
        }
        Map<String, List<WorkspaceDoris>> pathsByWorkspace = paths.findAll().stream()
                .collect(Collectors.groupingBy(WorkspaceDoris::getWorkspaceName));
        Map<String, Long> grantCounts = grantCountsByPath();
        String needle = q.trim().toLowerCase();
        List<WorkspaceRow> rows = workspaces.findAllByOrderByWorkspaceName().stream().map(w -> {
            List<WorkspaceDoris> opened = pathsByWorkspace.getOrDefault(w.getWorkspaceName(), List.of());
            return new WorkspaceRow(w.getWorkspaceName(),
                    opened.stream().map(WorkspaceDoris::getClusterName).sorted().toList(),
                    opened.stream().mapToLong(p -> grantCounts.getOrDefault(p.getId(), 0L)).sum());
        })
                .filter(r -> r.workspaceName().toLowerCase().contains(needle))
                .filter(r -> fab.isEmpty() || r.fabs().contains(fab))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("total", workspaces.count());
        model.addAttribute("clusters", clusterNames());
        model.addAttribute("q", q);
        model.addAttribute("fab", fab);
        return "workspaces";
    }

    @GetMapping("/workspaces/{workspace}")
    String detail(@PathVariable String workspace, Model model) {
        if (!workspaces.existsById(workspace)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到 workspace " + workspace);
        }
        Map<String, DorisHost> hostsById = hosts.findAllByOrderByClusterNameAscInstanceNameAsc().stream()
                .collect(Collectors.toMap(DorisHost::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> grantCounts = grantCountsByPath();
        Map<String, Long> saCounts = sas.findAll().stream()
                .collect(Collectors.groupingBy(DorisSa::getWorkspaceDorisId, Collectors.counting()));
        List<FabRow> opened = paths.findByWorkspaceName(workspace).stream()
                .sorted((a, b) -> a.getClusterName().compareTo(b.getClusterName()))
                .map(p -> new FabRow(p.getClusterName(), p, hostsById.get(p.getDorisHostId()),
                        saCounts.getOrDefault(p.getId(), 0L), grantCounts.getOrDefault(p.getId(), 0L)))
                .toList();
        Set<String> openedFabs = opened.stream().map(FabRow::clusterName).collect(Collectors.toSet());
        // Hosts of the fabs this workspace has not opened yet, grouped by fab
        Map<String, List<DorisHost>> openChoices = hostsById.values().stream()
                .filter(h -> !openedFabs.contains(h.getClusterName()))
                .collect(Collectors.groupingBy(DorisHost::getClusterName, LinkedHashMap::new, Collectors.toList()));
        model.addAttribute("workspace", workspace);
        model.addAttribute("opened", opened);
        model.addAttribute("openChoices", openChoices);
        model.addAttribute("fabCount", clusters.count());
        return "workspace";
    }

    @GetMapping("/workspaces/{workspace}/fabs/{cluster}")
    String fab(@PathVariable String workspace, @PathVariable String cluster, Model model) {
        WorkspaceDoris path = paths.findByWorkspaceNameAndClusterName(workspace, cluster)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        workspace + " 在 " + cluster + " 沒有 doris path"));
        Map<String, String> fabByPath = paths.findByWorkspaceName(workspace).stream()
                .collect(Collectors.toMap(WorkspaceDoris::getId, WorkspaceDoris::getClusterName));
        model.addAttribute("workspace", workspace);
        model.addAttribute("cluster", cluster);
        model.addAttribute("path", path);
        model.addAttribute("host", hosts.findById(path.getDorisHostId()).orElse(null));
        model.addAttribute("sas", sas.findByWorkspaceDorisIdOrderByUsername(path.getId()).stream()
                .map(sa -> new SaPanel(sa,
                        grants.findBySaIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(sa.getId())))
                .toList());
        model.addAttribute("copyTargets", sas.findByWorkspaceDorisIdIn(List.copyOf(fabByPath.keySet())).stream()
                .map(sa -> new CopyTarget(sa.getId(),
                        fabByPath.get(sa.getWorkspaceDorisId()) + " · " + sa.getUsername()))
                .sorted((a, b) -> a.label().compareTo(b.label()))
                .toList());
        model.addAttribute("privileges", PermissionService.PRIVILEGES.stream().sorted().toList());
        model.addAttribute("usernamePrefix", "sa_" + workspace.replaceAll("[^A-Za-z0-9]", "_") + "_");
        return "fab";
    }

    /** The fab follows from the chosen host, since every host belongs to exactly one fab. */
    @PostMapping("/workspaces/{workspace}/paths")
    String openPath(@PathVariable String workspace, @RequestParam String hostId, RedirectAttributes flash) {
        DorisHost host = hosts.findById(hostId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到 Doris host"));
        service.openPath(workspace, host.getClusterName(), hostId);
        flash.addFlashAttribute("notice", host.getClusterName() + " 已開通 doris path，接著新增 SA");
        return backToFab(workspace, host.getClusterName());
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/path/delete")
    String clearPath(@PathVariable String workspace, @PathVariable String cluster, RedirectAttributes flash) {
        service.clearPath(workspace, cluster);
        flash.addFlashAttribute("notice", cluster + " 的 doris path 已清空");
        return back(workspace);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/sas")
    String createSa(@PathVariable String workspace, @PathVariable String cluster, @RequestParam String username,
            @RequestParam(defaultValue = "") String description, RedirectAttributes flash) {
        PermissionService.NewPassword created = service.createSa(workspace, cluster, username.trim(),
                description.trim());
        flash.addFlashAttribute("shownPassword", new ShownPassword(created.sa().getUsername(), created.password()));
        return backToFab(workspace, cluster);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/sas/{saId}/password")
    String regeneratePassword(@PathVariable String workspace, @PathVariable String cluster, @PathVariable String saId,
            RedirectAttributes flash) {
        PermissionService.NewPassword renewed = service.regeneratePassword(workspace, cluster, saId);
        flash.addFlashAttribute("shownPassword", new ShownPassword(renewed.sa().getUsername(), renewed.password()));
        return backToFab(workspace, cluster);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/sas/{saId}/delete")
    String deleteSa(@PathVariable String workspace, @PathVariable String cluster, @PathVariable String saId,
            RedirectAttributes flash) {
        service.deleteSa(workspace, cluster, saId);
        flash.addFlashAttribute("notice", "已刪除 SA，同步後會從 " + cluster + " 的 Doris 移除");
        return backToFab(workspace, cluster);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/sas/{saId}/grants")
    String addGrant(@PathVariable String workspace, @PathVariable String cluster, @PathVariable String saId,
            @RequestParam String catalog, @RequestParam String database, @RequestParam String table,
            @RequestParam String privilege, RedirectAttributes flash) {
        service.addGrant(workspace, cluster, saId, catalog.trim(), database.trim(), table.trim(), privilege);
        flash.addFlashAttribute("notice", "已授予 " + privilege + " ON "
                + catalog.trim() + "." + database.trim() + "." + table.trim());
        return backToFab(workspace, cluster);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/grants/{grantId}/delete")
    String removeGrant(@PathVariable String workspace, @PathVariable String cluster, @PathVariable long grantId,
            RedirectAttributes flash) {
        service.removeGrant(workspace, cluster, grantId);
        flash.addFlashAttribute("notice", "已移除授權");
        return backToFab(workspace, cluster);
    }

    @PostMapping("/workspaces/{workspace}/fabs/{cluster}/sas/{saId}/grants/copy")
    String copyGrants(@PathVariable String workspace, @PathVariable String cluster, @PathVariable String saId,
            @RequestParam(defaultValue = "") List<String> targets, RedirectAttributes flash) {
        int added = service.copyGrants(workspace, cluster, saId, targets);
        flash.addFlashAttribute("notice", "已複製到 " + targets.size() + " 個 SA，共新增 " + added + " 筆授權");
        return backToFab(workspace, cluster);
    }

    /** Grant count per doris path (workspace_doris id), over all its SAs. */
    private Map<String, Long> grantCountsByPath() {
        Map<String, String> pathBySa = sas.findAll().stream()
                .collect(Collectors.toMap(DorisSa::getId, DorisSa::getWorkspaceDorisId));
        return grants.findAll().stream()
                .collect(Collectors.groupingBy(g -> pathBySa.get(g.getSaId()), Collectors.counting()));
    }

    private List<String> clusterNames() {
        return clusters.findAllByOrderByLogicClusterName().stream().map(c -> c.getLogicClusterName()).toList();
    }

    private static String back(String workspace) {
        return "redirect:/workspaces/" + workspace;
    }

    private static String backToFab(String workspace, String cluster) {
        return "redirect:/workspaces/" + workspace + "/fabs/" + cluster;
    }
}

package poc.doris.console.web;

import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import poc.doris.console.domain.Workspace;
import poc.doris.console.domain.WorkspaceRepository;

/** Workspace names for the search box in the top bar, which is on every page. */
@ControllerAdvice
class NavAdvice {

    private final WorkspaceRepository workspaces;

    NavAdvice(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @ModelAttribute("workspaceNames")
    List<String> workspaceNames() {
        return workspaces.findAllByOrderByWorkspaceName().stream().map(Workspace::getWorkspaceName).toList();
    }
}

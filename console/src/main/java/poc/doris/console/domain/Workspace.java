package poc.doris.console.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stand-in for ADMC's workspaces table. */
@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    private String workspaceName;

    protected Workspace() {
    }

    public String getWorkspaceName() {
        return workspaceName;
    }
}

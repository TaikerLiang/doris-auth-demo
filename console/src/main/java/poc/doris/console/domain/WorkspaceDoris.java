package poc.doris.console.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A workspace's doris path in one fab: it may use this fab's Doris host. Its SAs are DorisSa rows. */
@Entity
@Table(name = "workspace_doris")
public class WorkspaceDoris {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;
    private String workspaceName;
    private String clusterName;
    @Column(columnDefinition = "char(36)")
    private String dorisHostId;

    protected WorkspaceDoris() {
    }

    public WorkspaceDoris(String workspaceName, String clusterName, String dorisHostId) {
        this.id = UUID.randomUUID().toString();
        this.workspaceName = workspaceName;
        this.clusterName = clusterName;
        this.dorisHostId = dorisHostId;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceName() {
        return workspaceName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getDorisHostId() {
        return dorisHostId;
    }
}

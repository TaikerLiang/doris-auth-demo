package poc.doris.cluster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A workspace's doris path on one host of this fab. The id is the sa_id. */
@Entity
@Table(name = "workspace_doris")
public class WorkspaceDoris {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;
    private String workspaceName;
    @Column(columnDefinition = "char(36)")
    private String dorisHostId;
    private String username;
    @Column(columnDefinition = "text")
    private String passwordEnc;
    private int keyVersion;

    protected WorkspaceDoris() {
    }

    public WorkspaceDoris(String id, String workspaceName, String dorisHostId, String username, String passwordEnc,
            int keyVersion) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.dorisHostId = dorisHostId;
        this.username = username;
        this.passwordEnc = passwordEnc;
        this.keyVersion = keyVersion;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceName() {
        return workspaceName;
    }

    public String getDorisHostId() {
        return dorisHostId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordEnc() {
        return passwordEnc;
    }
}

package poc.doris.console.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A workspace's doris path in one fab, and the SA it uses there. The id is the sa_id. */
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
    private String username;
    @Column(columnDefinition = "text")
    private String passwordEnc;
    private int keyVersion;

    protected WorkspaceDoris() {
    }

    public WorkspaceDoris(String workspaceName, String clusterName, String dorisHostId, String username,
            String passwordEnc, int keyVersion) {
        this.id = UUID.randomUUID().toString();
        this.workspaceName = workspaceName;
        this.clusterName = clusterName;
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

    public String getClusterName() {
        return clusterName;
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

    public int getKeyVersion() {
        return keyVersion;
    }
}

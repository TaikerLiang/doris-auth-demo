package poc.doris.console.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** A service account on a workspace's doris path. The id is the sa_id DAO uses to find its credential. */
@Entity
@Table(name = "doris_sas")
public class DorisSa {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;
    @Column(columnDefinition = "char(36)")
    private String workspaceDorisId;
    @Column(columnDefinition = "char(36)")
    private String dorisHostId;
    private String username;
    private String description;
    @Column(columnDefinition = "text")
    private String passwordEnc;
    private int keyVersion;
    private LocalDateTime createdAt;

    protected DorisSa() {
    }

    public DorisSa(WorkspaceDoris path, String username, String description, String passwordEnc, int keyVersion) {
        this.id = UUID.randomUUID().toString();
        this.workspaceDorisId = path.getId();
        this.dorisHostId = path.getDorisHostId();
        this.username = username;
        this.description = description;
        this.passwordEnc = passwordEnc;
        this.keyVersion = keyVersion;
        this.createdAt = LocalDateTime.now();
    }

    public void newPassword(String passwordEnc, int keyVersion) {
        this.passwordEnc = passwordEnc;
        this.keyVersion = keyVersion;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceDorisId() {
        return workspaceDorisId;
    }

    public String getDorisHostId() {
        return dorisHostId;
    }

    public String getUsername() {
        return username;
    }

    public String getDescription() {
        return description;
    }

    public String getPasswordEnc() {
        return passwordEnc;
    }

    public int getKeyVersion() {
        return keyVersion;
    }
}

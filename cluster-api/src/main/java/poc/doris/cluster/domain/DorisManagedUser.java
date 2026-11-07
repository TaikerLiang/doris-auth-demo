package poc.doris.cluster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** An SA this Cluster created on a Doris host. password_hash tells whether Doris has the current password. */
@Entity
@Table(name = "doris_managed_users")
public class DorisManagedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "char(36)")
    private String dorisHostId;
    private String username;
    @Column(columnDefinition = "char(36)")
    private String saId;
    @Column(columnDefinition = "char(64)")
    private String passwordHash;
    private LocalDateTime createdAt;

    protected DorisManagedUser() {
    }

    public DorisManagedUser(String dorisHostId, String username, String saId, String passwordHash) {
        this.dorisHostId = dorisHostId;
        this.username = username;
        this.saId = saId;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
    }

    public void update(String saId, String passwordHash) {
        this.saId = saId;
        this.passwordHash = passwordHash;
    }

    public String getUsername() {
        return username;
    }

    public String getSaId() {
        return saId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}

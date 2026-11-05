package poc.doris.console.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Per-fab sync state: the version Console wants vs. the version the fab reports as applied. */
@Entity
@Table(name = "doris_cluster_sync")
public class DorisClusterSync {

    @Id
    private String clusterName;
    private long desiredVersion;
    private Long appliedVersion;
    @Column(columnDefinition = "text")
    private String lastError;
    private LocalDateTime updatedAt;

    protected DorisClusterSync() {
    }

    public DorisClusterSync(String clusterName) {
        this.clusterName = clusterName;
        this.updatedAt = LocalDateTime.now();
    }

    public long nextDesiredVersion() {
        desiredVersion++;
        updatedAt = LocalDateTime.now();
        return desiredVersion;
    }

    public String getClusterName() {
        return clusterName;
    }

    public long getDesiredVersion() {
        return desiredVersion;
    }

    public Long getAppliedVersion() {
        return appliedVersion;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

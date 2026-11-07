package poc.doris.cluster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** The newest version received from Console and the newest version fully applied to Doris and Redis. */
@Entity
@Table(name = "doris_sync_state")
public class DorisSyncState {

    @Id
    private String clusterName;
    private long receivedVersion;
    private Long appliedVersion;
    @Column(columnDefinition = "text")
    private String lastError;
    private int lastDrift;
    private LocalDateTime updatedAt;

    protected DorisSyncState() {
    }

    public DorisSyncState(String clusterName) {
        this.clusterName = clusterName;
        this.updatedAt = LocalDateTime.now();
    }

    public void received(long version) {
        receivedVersion = version;
        updatedAt = LocalDateTime.now();
    }

    /** {@code error} null means every statement succeeded, so {@code version} counts as applied. */
    public void applied(long version, String error, int drift) {
        if (error == null) {
            appliedVersion = version;
        }
        lastError = error;
        lastDrift = drift;
        updatedAt = LocalDateTime.now();
    }

    public String getClusterName() {
        return clusterName;
    }

    public long getReceivedVersion() {
        return receivedVersion;
    }

    public Long getAppliedVersion() {
        return appliedVersion;
    }

    public String getLastError() {
        return lastError;
    }

    public int getLastDrift() {
        return lastDrift;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

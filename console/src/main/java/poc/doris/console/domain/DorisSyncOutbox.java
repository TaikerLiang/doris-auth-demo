package poc.doris.console.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Sync segment 1 event: "push fab X's full desired state at version N to its Cluster API". */
@Entity
@Table(name = "doris_sync_outbox")
public class DorisSyncOutbox {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String clusterName;
    private long desiredVersion;
    private String status;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime nextAttemptAt;

    protected DorisSyncOutbox() {
    }

    public DorisSyncOutbox(String clusterName, long desiredVersion) {
        this.clusterName = clusterName;
        this.desiredVersion = desiredVersion;
        this.status = PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
    }

    /** The Cluster API accepted a snapshot at this event's version or newer. */
    public void sent() {
        status = SENT;
    }

    public void failed(LocalDateTime nextAttemptAt) {
        retryCount++;
        this.nextAttemptAt = nextAttemptAt;
    }

    public Long getId() {
        return id;
    }

    public String getClusterName() {
        return clusterName;
    }

    public long getDesiredVersion() {
        return desiredVersion;
    }

    public String getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }
}

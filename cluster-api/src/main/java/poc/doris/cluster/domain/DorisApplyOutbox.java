package poc.doris.cluster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Sync segment 2 event: "apply the Cluster DB state (version N) to Doris and Redis". */
@Entity
@Table(name = "doris_apply_outbox")
public class DorisApplyOutbox {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long version;
    private String status;
    private int retryCount;
    private LocalDateTime createdAt;

    protected DorisApplyOutbox() {
    }

    public DorisApplyOutbox(long version) {
        this.version = version;
        this.status = PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void done() {
        status = DONE;
    }

    public void failed() {
        retryCount++;
    }

    public long getVersion() {
        return version;
    }
}

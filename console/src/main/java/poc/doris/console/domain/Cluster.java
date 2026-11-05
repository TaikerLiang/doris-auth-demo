package poc.doris.console.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stand-in for ADMC's clusters table: one row per fab. */
@Entity
@Table(name = "clusters")
public class Cluster {

    @Id
    private String logicClusterName;

    protected Cluster() {
    }

    public String getLogicClusterName() {
        return logicClusterName;
    }
}

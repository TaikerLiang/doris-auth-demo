package poc.doris.cluster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A Doris instance of this fab, as last pushed by Console. */
@Entity
@Table(name = "doris_hosts")
public class DorisHost {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;
    private String instanceName;
    private String feHost;
    private int queryPort;
    private int httpPort;
    private String secretRef;

    protected DorisHost() {
    }

    public DorisHost(String id, String instanceName, String feHost, int queryPort, int httpPort, String secretRef) {
        this.id = id;
        this.instanceName = instanceName;
        this.feHost = feHost;
        this.queryPort = queryPort;
        this.httpPort = httpPort;
        this.secretRef = secretRef;
    }

    public String getId() {
        return id;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public String getFeHost() {
        return feHost;
    }

    public int getQueryPort() {
        return queryPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public String getSecretRef() {
        return secretRef;
    }
}

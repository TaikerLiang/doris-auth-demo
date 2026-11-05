package poc.doris.console.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One Doris instance (host:port), owned by exactly one fab. The endpoint never changes after creation. */
@Entity
@Table(name = "doris_hosts")
public class DorisHost {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;
    private String clusterName;
    private String instanceName;
    private String feHost;
    private int queryPort;
    private int httpPort;
    private String secretRef;

    protected DorisHost() {
    }

    public DorisHost(String clusterName, String instanceName, String feHost, int queryPort, int httpPort,
            String secretRef) {
        this.id = UUID.randomUUID().toString();
        this.clusterName = clusterName;
        this.instanceName = instanceName;
        this.feHost = feHost;
        this.queryPort = queryPort;
        this.httpPort = httpPort;
        this.secretRef = secretRef;
    }

    public String getId() {
        return id;
    }

    public String getClusterName() {
        return clusterName;
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

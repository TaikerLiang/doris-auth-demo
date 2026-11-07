package poc.doris.console.service;

import java.util.List;

/**
 * A fab's full desired state at one version, as PUT to its Cluster API (/doris/state). Same JSON as
 * cluster-api's Snapshot; the password is plaintext in transit and re-encrypted by the Cluster (decision 11).
 */
public record Snapshot(String cluster, long version, List<Host> hosts, List<Path> paths, List<Grant> grants) {

    public record Host(String id, String instanceName, String feHost, int queryPort, int httpPort,
            String secretRef) {
    }

    public record Path(String saId, String workspace, String hostId, String username, String password) {
    }

    public record Grant(String saId, String catalog, String database, String table, String privilege) {
    }
}

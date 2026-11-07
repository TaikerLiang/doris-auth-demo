package poc.doris.cluster.service;

import java.util.List;

/** The full desired state of one fab at one version, as pushed by Console (PUT /doris/state). */
public record Snapshot(String cluster, long version, List<Host> hosts, List<Path> paths, List<Grant> grants) {

    public record Host(String id, String instanceName, String feHost, int queryPort, int httpPort,
            String secretRef) {
    }

    /** {@code password} is plaintext in transit (TLS in real deployments) and encrypted again at rest. */
    public record Path(String saId, String workspace, String hostId, String username, String password) {
    }

    public record Grant(String saId, String catalog, String database, String table, String privilege) {
    }
}

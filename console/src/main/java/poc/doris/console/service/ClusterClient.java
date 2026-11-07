package poc.doris.console.service;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP to each fab's Cluster API, the only thing Console calls across fabs. */
@Component
@EnableConfigurationProperties(ClusterClient.Endpoints.class)
public class ClusterClient {

    /** {@code console.clusters.endpoints.<fab>: http://...}; a fab without an endpoint is never synced. */
    @ConfigurationProperties(prefix = "console.clusters")
    public record Endpoints(Map<String, String> endpoints) {
    }

    public record PutResult(boolean accepted, long receivedVersion) {
    }

    public record Status(String cluster, long receivedVersion, Long appliedVersion, String lastError,
            int lastDrift) {
    }

    private final Map<String, String> endpoints;
    private final RestClient http;

    /** Boot's builder, so JSON uses Boot's ObjectMapper (unknown fields such as updatedAt are ignored). */
    public ClusterClient(Endpoints config, RestClient.Builder builder) {
        this.endpoints = config.endpoints() == null ? Map.of() : config.endpoints();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = builder.requestFactory(factory).build();
    }

    public String endpoint(String cluster) {
        return endpoints.get(cluster);
    }

    public PutResult putState(Snapshot snapshot) {
        return http.put().uri(url(snapshot.cluster(), "/doris/state")).body(snapshot).retrieve()
                .body(PutResult.class);
    }

    /** Asks the fab to apply its current state again; used when it already had the version we sent. */
    public void trigger(String cluster) {
        http.post().uri(url(cluster, "/doris/trigger")).retrieve().toBodilessEntity();
    }

    public Status status(String cluster) {
        return http.get().uri(url(cluster, "/doris/status")).retrieve().body(Status.class);
    }

    private String url(String cluster, String path) {
        String base = endpoints.get(cluster);
        if (base == null) {
            throw new IllegalStateException(cluster + " 沒有設定 Cluster API endpoint");
        }
        return base + path;
    }
}

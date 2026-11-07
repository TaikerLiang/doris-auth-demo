package poc.doris.cluster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * Publishes SA credentials to this fab's Redis for DAO: {@code doris:<instance>:cred:<sa_id>} holds JSON with
 * FE host, port, username and password, and {@code doris:<instance>:cred_version} is bumped after any change.
 * Plain strings (StringCodec) so DAO can read them without Java type info.
 */
@Component
public class CredentialStore {

    public record Cred(String feHost, int queryPort, String username, String password) {
    }

    private final RedissonClient redis;
    private final ObjectMapper json;

    public CredentialStore(RedissonClient redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * Makes the instance's cred keys match {@code creds} (sa_id → cred), leaving the keys of {@code keep} as
     * they are. Returns whether anything changed.
     */
    public boolean publish(String instanceName, Map<String, Cred> creds, Set<String> keep) {
        String prefix = "doris:" + instanceName + ":cred:";
        boolean changed = false;
        for (Map.Entry<String, Cred> e : creds.entrySet()) {
            String value = toJson(e.getValue());
            var bucket = redis.<String>getBucket(prefix + e.getKey(), StringCodec.INSTANCE);
            if (!value.equals(bucket.get())) {
                bucket.set(value);
                changed = true;
            }
        }
        for (String key : redis.getKeys().getKeysByPattern(prefix + "*")) {
            String saId = key.substring(prefix.length());
            if (!creds.containsKey(saId) && !keep.contains(saId)) {
                redis.getBucket(key).delete();
                changed = true;
            }
        }
        // Written after the creds, so DAO never sees a new version with an old credential
        if (changed) {
            redis.getAtomicLong("doris:" + instanceName + ":cred_version").incrementAndGet();
        }
        return changed;
    }

    private String toJson(Cred cred) {
        try {
            return json.writeValueAsString(cred);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

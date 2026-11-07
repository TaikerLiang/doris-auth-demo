package poc.doris.cluster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.RBatch;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * Publishes SA credentials to this fab's Redis for DAO: {@code doris:<instance>:cred:<sa_id>} holds JSON with
 * FE host, port, username and password, and {@code doris:<instance>:cred_version} is bumped with any change.
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
        // MULTI/EXEC: the cred changes and the cred_version bump land together or not at all. Otherwise a
        // failure between them would leave creds updated, and the retry would see nothing left to change
        // and never bump the version, so DAO would keep the old password.
        RBatch batch = redis.createBatch(BatchOptions.defaults().executionMode(ExecutionMode.IN_MEMORY_ATOMIC));
        boolean changed = false;
        for (Map.Entry<String, Cred> e : creds.entrySet()) {
            String value = toJson(e.getValue());
            if (!value.equals(redis.<String>getBucket(prefix + e.getKey(), StringCodec.INSTANCE).get())) {
                batch.<String>getBucket(prefix + e.getKey(), StringCodec.INSTANCE).setAsync(value);
                changed = true;
            }
        }
        for (String key : redis.getKeys().getKeysByPattern(prefix + "*")) {
            String saId = key.substring(prefix.length());
            if (!creds.containsKey(saId) && !keep.contains(saId)) {
                batch.getBucket(key, StringCodec.INSTANCE).deleteAsync();
                changed = true;
            }
        }
        if (changed) {
            batch.getAtomicLong("doris:" + instanceName + ":cred_version").incrementAndGetAsync();
            batch.execute();
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

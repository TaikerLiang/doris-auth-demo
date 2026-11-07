package poc.doris.console.scheduler;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Console's own Redis, used only for scheduler locks; it never holds SA credentials. */
@Configuration
class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${console.redis.address}") String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}

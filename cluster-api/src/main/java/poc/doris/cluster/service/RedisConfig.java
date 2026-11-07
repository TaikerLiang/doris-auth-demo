package poc.doris.cluster.service;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This fab's Redis, the one DAO reads credentials from. */
@Configuration
class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${cluster.redis.address}") String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}

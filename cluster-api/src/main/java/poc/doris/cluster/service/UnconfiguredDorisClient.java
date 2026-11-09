package poc.doris.cluster.service;

import java.sql.SQLException;
import org.springframework.stereotype.Component;
import poc.doris.cluster.domain.DorisHost;

/**
 * Default {@link DorisClient}: refuses to connect. Every apply then fails per host with this message in
 * {@code lastError}, and Redis is left untouched, so nothing pretends a grant was applied.
 */
@Component
public class UnconfiguredDorisClient implements DorisClient {

    @Override
    public Session open(DorisHost host) throws SQLException {
        throw new SQLException("沒有 DorisClient 實作：此 repo 不含連線 Doris 的程式，請提供 @Primary 的 DorisClient bean");
    }
}

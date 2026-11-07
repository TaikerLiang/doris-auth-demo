package poc.doris.cluster.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import poc.doris.cluster.domain.DorisHost;

/** Talks to a Doris FE with this fab's platform admin account, which the host's secret_ref points to. */
@Component
public class DorisAdmin {

    private final Environment env;

    public DorisAdmin(Environment env) {
        this.env = env;
    }

    public Connection connect(DorisHost host) throws SQLException {
        String credential = env.getProperty(host.getSecretRef());
        if (credential == null || !credential.contains(":")) {
            throw new SQLException("secret_ref " + host.getSecretRef() + " 沒有設定（格式 user:password）");
        }
        int colon = credential.indexOf(':');
        String url = "jdbc:mysql://" + host.getFeHost() + ":" + host.getQueryPort()
                + "/?connectTimeout=5000&socketTimeout=60000";
        return DriverManager.getConnection(url, credential.substring(0, colon), credential.substring(colon + 1));
    }

    /** Database and table privileges of every user@'%' on this Doris. */
    public Map<String, Set<DorisGrant>> readGrants(Connection conn) throws SQLException {
        Map<String, Set<DorisGrant>> users = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW ALL GRANTS")) {
            while (rs.next()) {
                String username = GrantParser.username(rs.getString("UserIdentity"));
                if (username != null) {
                    users.put(username, GrantParser.parse(rs.getString("DatabasePrivs"), rs.getString("TablePrivs")));
                }
            }
        }
        return users;
    }

    public void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}

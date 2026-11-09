package poc.doris.cluster.service;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import poc.doris.cluster.domain.DorisHost;

/**
 * How {@link Reconciler} talks to one Doris FE. This repo ships no JDBC implementation (see
 * {@link UnconfiguredDorisClient}); a deployment supplies its own bean annotated {@code @Primary}.
 */
public interface DorisClient {

    /** Opens a session on {@code host} with the fab's platform admin account, which {@code secret_ref} points to. */
    Session open(DorisHost host) throws SQLException;

    /** One connection to one FE; Reconciler opens one per host per run and closes it when done. */
    interface Session extends AutoCloseable {

        /** Database and table privileges of every user@'%', as {@link GrantParser} reads {@code SHOW ALL GRANTS}. */
        Map<String, Set<DorisGrant>> readGrants() throws SQLException;

        /**
         * Runs one statement from {@link ReconcilePlanner}. Every value in it has passed {@link SqlSafe}.
         * Log only {@link ReconcilePlanner.Sql#display()}: {@code sql()} may contain a password.
         */
        void apply(ReconcilePlanner.Sql statement) throws SQLException;

        @Override
        void close() throws SQLException;
    }
}

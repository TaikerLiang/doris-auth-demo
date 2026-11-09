package poc.doris.cluster.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whitelist for every value that is spliced into a Doris DDL/DCL statement. CREATE/ALTER/DROP USER, GRANT and
 * REVOKE take identifiers, not bind variables, so JDBC cannot parameterize them; the only defence is to accept
 * plain values only. {@link StateService} applies this at the API boundary and {@link ReconcilePlanner} again
 * when it builds each statement, so no caller can reach {@link DorisAdmin#execute} with an unchecked value.
 */
public final class SqlSafe {

    public static final Set<String> PRIVILEGES =
            Set.of("SELECT_PRIV", "LOAD_PRIV", "ALTER_PRIV", "CREATE_PRIV", "DROP_PRIV");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    // Console builds "sa_" + workspace name (up to 64), and its username column is VARCHAR(128)
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9]{16,128}");

    private SqlSafe() {
    }

    /** Catalog, database or table name; table may also be {@code *}. */
    public static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    public static boolean isUsername(String value) {
        return value != null && USERNAME.matcher(value).matches();
    }

    public static boolean isPassword(String value) {
        return value != null && PASSWORD.matcher(value).matches();
    }

    public static boolean isPrivilege(String value) {
        return value != null && PRIVILEGES.contains(value);
    }

    public static boolean isGrant(DorisGrant g) {
        return isIdentifier(g.catalog()) && isIdentifier(g.database())
                && ("*".equals(g.table()) || isIdentifier(g.table())) && isPrivilege(g.privilege());
    }

    /** Returns {@code value} unchanged, or throws if it fails {@code check}. Used where SQL is built. */
    public static String require(String value, boolean check, String what) {
        if (!check) {
            throw new IllegalArgumentException(what + " 不是安全的 SQL 值：" + value);
        }
        return value;
    }
}

package poc.doris.cluster.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whitelist for every value that is spliced into a Doris DDL/DCL statement. CREATE/ALTER/DROP USER, GRANT and
 * REVOKE take identifiers, not bind variables, so JDBC cannot parameterize them; the only defence is to accept
 * plain values only. {@link StateService} applies this at the API boundary and {@link ReconcilePlanner} again
 * when it builds each statement, so no caller can reach {@link DorisClient.Session#apply} with an unchecked value.
 */
public final class SqlSafe {

    public static final Set<String> PRIVILEGES =
            Set.of("SELECT_PRIV", "LOAD_PRIV", "ALTER_PRIV", "CREATE_PRIV", "DROP_PRIV");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    // Console builds "sa_" + workspace name (up to 64), and its username column is VARCHAR(128)
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9]{16,128}");
    // No quotes, whitespace, dots or punctuation: cannot end a string, a name or a statement
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,255}");

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

    /** A grant from desired state: names and privilege must be ones the Console is allowed to hand out. */
    public static boolean isGrant(DorisGrant g) {
        return isIdentifier(g.catalog()) && isIdentifier(g.database())
                && ("*".equals(g.table()) || isIdentifier(g.table())) && isPrivilege(g.privilege());
    }

    /**
     * A grant read back from Doris that we are about to REVOKE. It may carry a privilege the Console never
     * grants (someone added GRANT_PRIV by hand) and we must still be able to take it away, so only the shape is
     * checked: each part is one token that cannot break out of the statement.
     */
    public static boolean isRevocable(DorisGrant g) {
        return isToken(g.catalog()) && isToken(g.database()) && ("*".equals(g.table()) || isToken(g.table()))
                && isToken(g.privilege());
    }

    private static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    /** Returns {@code value} unchanged, or throws if it fails {@code check}. Used where SQL is built. */
    public static String require(String value, boolean check, String what) {
        if (!check) {
            throw new IllegalArgumentException(what + " 不是安全的 SQL 值：" + value);
        }
        return value;
    }
}

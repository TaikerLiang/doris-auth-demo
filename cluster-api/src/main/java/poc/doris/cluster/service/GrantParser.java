package poc.doris.cluster.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the DatabasePrivs / TablePrivs columns of Doris 4.1 {@code SHOW ALL GRANTS}, e.g.
 * {@code internal.olist: Select_priv,Alter_priv; internal.mysql: Select_priv}.
 */
public final class GrantParser {

    /** Every Doris user gets SELECT on these; they are not ours to manage. */
    static final Set<String> BUILT_IN_DATABASES = Set.of("information_schema", "mysql", "__internal_schema");

    private static final Pattern USER_IDENTITY = Pattern.compile("'([^']*)'@'([^']*)'");

    private GrantParser() {
    }

    /** {@code 'sa_x'@'%'} → {@code sa_x}; null when the identity is not for host '%'. */
    public static String username(String userIdentity) {
        Matcher m = USER_IDENTITY.matcher(userIdentity);
        return m.matches() && m.group(2).equals("%") ? m.group(1) : null;
    }

    public static Set<DorisGrant> parse(String databasePrivs, String tablePrivs) {
        Set<DorisGrant> grants = new HashSet<>();
        parseColumn(databasePrivs, 2, grants);
        parseColumn(tablePrivs, 3, grants);
        return grants;
    }

    /** {@code parts} is 2 for catalog.database and 3 for catalog.database.table. */
    private static void parseColumn(String column, int parts, Set<DorisGrant> into) {
        if (column == null || column.isBlank() || column.equals("NULL")) {
            return;
        }
        for (String entry : column.split(";")) {
            int colon = entry.lastIndexOf(':');
            String[] target = entry.substring(0, colon).trim().split("\\.");
            if (target.length != parts) {
                throw new IllegalArgumentException("unexpected privilege target: " + entry.trim());
            }
            if (BUILT_IN_DATABASES.contains(target[1])) {
                continue;
            }
            String table = parts == 3 ? target[2] : "*";
            for (String priv : entry.substring(colon + 1).split(",")) {
                into.add(new DorisGrant(target[0], target[1], table, priv.trim().toUpperCase(Locale.ROOT)));
            }
        }
    }
}

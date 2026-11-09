package poc.doris.cluster.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Diffs the desired SAs of one Doris host against what Doris has. Pure: no I/O, so it is unit tested
 * against captured {@code SHOW ALL GRANTS} output. Only users in the managed list are ever altered or dropped.
 */
public final class ReconcilePlanner {

    public record DesiredSa(String saId, String username, String password, Set<DorisGrant> grants) {
    }

    /** A row of doris_managed_users: an SA this Cluster created on the host. */
    public record ManagedSa(String saId, String passwordHash) {
    }

    public enum Kind { CREATE_USER, ALTER_USER, DROP_USER, REVOKE, GRANT }

    /** {@code display} is safe to log and to report back; it never contains the password. */
    public record Sql(Kind kind, String sql, String display) {
    }

    /**
     * What to do for one username. {@code conflict}: a user with this name exists but was not created by us,
     * so we leave it alone. {@code forget}: drop it from the managed list once the statements succeed.
     */
    public record SaPlan(String username, String saId, boolean conflict, boolean forget, List<Sql> statements) {
    }

    private static final Comparator<DorisGrant> ORDER =
            Comparator.comparing(DorisGrant::target).thenComparing(DorisGrant::privilege);

    private ReconcilePlanner() {
    }

    public static List<SaPlan> plan(List<DesiredSa> desired, Map<String, Set<DorisGrant>> actual,
            Map<String, ManagedSa> managed) {
        List<SaPlan> plans = new ArrayList<>();
        Set<String> desiredNames = new TreeSet<>();
        for (DesiredSa sa : desired) {
            desiredNames.add(sa.username());
            Set<DorisGrant> has = actual.get(sa.username());
            ManagedSa known = managed.get(sa.username());
            List<Sql> sqls = new ArrayList<>();
            if (has == null) {
                // New SA, or a managed SA someone dropped by hand
                sqls.add(userSql(Kind.CREATE_USER, "CREATE USER", sa));
                sorted(sa.grants()).forEach(g -> sqls.add(grantSql(Kind.GRANT, g, sa.username())));
            } else if (known == null) {
                plans.add(new SaPlan(sa.username(), sa.saId(), true, false, List.of()));
                continue;
            } else {
                if (!known.saId().equals(sa.saId()) || !known.passwordHash().equals(hash(sa.password()))) {
                    sqls.add(userSql(Kind.ALTER_USER, "ALTER USER", sa));
                }
                minus(has, sa.grants()).forEach(g -> sqls.add(grantSql(Kind.REVOKE, g, sa.username())));
                minus(sa.grants(), has).forEach(g -> sqls.add(grantSql(Kind.GRANT, g, sa.username())));
            }
            plans.add(new SaPlan(sa.username(), sa.saId(), false, false, sqls));
        }
        for (Map.Entry<String, ManagedSa> e : new TreeMap<>(managed).entrySet()) {
            String username = e.getKey();
            if (desiredNames.contains(username)) {
                continue;
            }
            List<Sql> sqls = actual.containsKey(username)
                    ? List.of(new Sql(Kind.DROP_USER, "DROP USER " + identity(username),
                            "DROP USER " + identity(username)))
                    : List.of();
            plans.add(new SaPlan(username, e.getValue().saId(), false, true, sqls));
        }
        return plans;
    }

    public static String hash(String password) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Values are whitelisted again here (StateService already did at the API boundary) so that no other
    // caller can turn a crafted name into SQL: these statements cannot be parameterized by JDBC.
    private static Sql userSql(Kind kind, String verb, DesiredSa sa) {
        String prefix = verb + " " + identity(sa.username()) + " IDENTIFIED BY ";
        SqlSafe.require("***", SqlSafe.isPassword(sa.password()), "password"); // never log the password
        return new Sql(kind, prefix + "'" + sa.password() + "'", prefix + "'***'");
    }

    private static Sql grantSql(Kind kind, DorisGrant g, String username) {
        // GRANT comes from desired state: full whitelist. REVOKE comes from Doris itself and may carry a
        // privilege the Console never grants, which we must still be able to remove: shape check only.
        SqlSafe.require(g.target() + " " + g.privilege(),
                kind == Kind.GRANT ? SqlSafe.isGrant(g) : SqlSafe.isRevocable(g), kind.name());
        String sql = kind == Kind.GRANT
                ? "GRANT " + g.privilege() + " ON " + g.target() + " TO " + identity(username)
                : "REVOKE " + g.privilege() + " ON " + g.target() + " FROM " + identity(username);
        return new Sql(kind, sql, sql);
    }

    private static String identity(String username) {
        return "'" + SqlSafe.require(username, SqlSafe.isUsername(username), "username") + "'@'%'";
    }

    private static List<DorisGrant> minus(Set<DorisGrant> a, Set<DorisGrant> b) {
        return a.stream().filter(g -> !b.contains(g)).sorted(ORDER).toList();
    }

    private static List<DorisGrant> sorted(Set<DorisGrant> grants) {
        return grants.stream().sorted(ORDER).toList();
    }
}

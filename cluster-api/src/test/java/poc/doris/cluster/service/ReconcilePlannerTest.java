package poc.doris.cluster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import poc.doris.cluster.service.ReconcilePlanner.DesiredSa;
import poc.doris.cluster.service.ReconcilePlanner.ManagedSa;
import poc.doris.cluster.service.ReconcilePlanner.SaPlan;
import poc.doris.cluster.service.ReconcilePlanner.Sql;

class ReconcilePlannerTest {

    // Captured from Doris 4.1.4 SHOW ALL GRANTS after granting SELECT on olist.orders, SELECT+LOAD on
    // olist.customers and SELECT+ALTER on olist.*
    static final String DB_PRIVS =
            "internal.information_schema: Select_priv; internal.mysql: Select_priv; internal.olist: Select_priv,Alter_priv";
    static final String TABLE_PRIVS =
            "internal.olist.customers: Select_priv,Load_priv; internal.olist.orders: Select_priv";

    static final DorisGrant ORDERS = new DorisGrant("internal", "olist", "orders", "SELECT_PRIV");
    static final DorisGrant CUSTOMERS = new DorisGrant("internal", "olist", "customers", "SELECT_PRIV");
    static final DorisGrant CUSTOMERS_LOAD = new DorisGrant("internal", "olist", "customers", "LOAD_PRIV");
    static final DorisGrant SELLERS = new DorisGrant("internal", "olist", "sellers", "SELECT_PRIV");
    static final DorisGrant OLIST = new DorisGrant("internal", "olist", "*", "SELECT_PRIV");
    static final DorisGrant OLIST_ALTER = new DorisGrant("internal", "olist", "*", "ALTER_PRIV");

    @Test
    void parsesDatabaseAndTablePrivsAndSkipsBuiltInDatabases() {
        assertThat(GrantParser.parse(DB_PRIVS, TABLE_PRIVS))
                .containsExactlyInAnyOrder(OLIST, OLIST_ALTER, CUSTOMERS, CUSTOMERS_LOAD, ORDERS);
        assertThat(GrantParser.parse("internal.information_schema: Select_priv; internal.mysql: Select_priv", "NULL"))
                .isEmpty();
        assertThat(GrantParser.parse(null, null)).isEmpty();
    }

    @Test
    void readsUsernameOnlyForAnyHost() {
        assertThat(GrantParser.username("'sa_x'@'%'")).isEqualTo("sa_x");
        assertThat(GrantParser.username("'sa_x'@'10.0.0.1'")).isNull();
    }

    @Test
    void createsMissingSaWithItsGrants() {
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(sa("1", "sa_a", "pw00000000000000", Set.of(ORDERS))), Map.of(), Map.of());

        assertThat(statements(plans)).containsExactly(
                "CREATE USER 'sa_a'@'%' IDENTIFIED BY 'pw00000000000000'",
                "GRANT SELECT_PRIV ON internal.olist.orders TO 'sa_a'@'%'");
        assertThat(plans.get(0).statements().get(0).display()).doesNotContain("pw00000000000000").contains("'***'");
    }

    @Test
    void revokesExtraAndGrantsMissing() {
        Map<String, Set<DorisGrant>> actual = Map.of("sa_a", GrantParser.parse(DB_PRIVS, TABLE_PRIVS));
        List<SaPlan> plans = ReconcilePlanner.plan(
                List.of(sa("1", "sa_a", "pw00000000000000", Set.of(ORDERS, CUSTOMERS, SELLERS))), actual,
                Map.of("sa_a", managed("1", "pw00000000000000")));

        assertThat(statements(plans)).containsExactly(
                "REVOKE ALTER_PRIV ON internal.olist.* FROM 'sa_a'@'%'",
                "REVOKE SELECT_PRIV ON internal.olist.* FROM 'sa_a'@'%'",
                "REVOKE LOAD_PRIV ON internal.olist.customers FROM 'sa_a'@'%'",
                "GRANT SELECT_PRIV ON internal.olist.sellers TO 'sa_a'@'%'");
    }

    @Test
    void inSyncSaNeedsNothing() {
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(sa("1", "sa_a", "pw00000000000000", Set.of(ORDERS))),
                Map.of("sa_a", Set.of(ORDERS)), Map.of("sa_a", managed("1", "pw00000000000000")));

        assertThat(statements(plans)).isEmpty();
    }

    @Test
    void changedPasswordOrSaIdAltersUser() {
        Map<String, Set<DorisGrant>> actual = Map.of("sa_a", Set.of());

        assertThat(statements(ReconcilePlanner.plan(List.of(sa("1", "sa_a", "new0000000000000", Set.of())), actual,
                Map.of("sa_a", managed("1", "old0000000000000"))))).containsExactly("ALTER USER 'sa_a'@'%' IDENTIFIED BY 'new0000000000000'");
        // Path cleared and opened again: same username, new sa_id
        assertThat(statements(ReconcilePlanner.plan(List.of(sa("2", "sa_a", "pw00000000000000", Set.of())), actual,
                Map.of("sa_a", managed("1", "pw00000000000000"))))).containsExactly("ALTER USER 'sa_a'@'%' IDENTIFIED BY 'pw00000000000000'");
    }

    @Test
    void dropsManagedSaThatIsNoLongerDesired() {
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(), Map.of("sa_a", Set.of(ORDERS)),
                Map.of("sa_a", managed("1", "pw00000000000000")));

        assertThat(plans).singleElement().satisfies(p -> {
            assertThat(p.forget()).isTrue();
            assertThat(p.statements()).extracting(Sql::sql).containsExactly("DROP USER 'sa_a'@'%'");
        });
        // Already gone from Doris: only forget it
        assertThat(ReconcilePlanner.plan(List.of(), Map.of(), Map.of("sa_a", managed("1", "pw00000000000000"))))
                .singleElement().satisfies(p -> assertThat(p.statements()).isEmpty());
    }

    @Test
    void recreatesManagedSaDroppedByHand() {
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(sa("1", "sa_a", "pw00000000000000", Set.of(ORDERS))), Map.of(),
                Map.of("sa_a", managed("1", "pw00000000000000")));

        assertThat(statements(plans)).containsExactly(
                "CREATE USER 'sa_a'@'%' IDENTIFIED BY 'pw00000000000000'",
                "GRANT SELECT_PRIV ON internal.olist.orders TO 'sa_a'@'%'");
    }

    @Test
    void neverTouchesUsersItDidNotCreate() {
        Map<String, Set<DorisGrant>> actual = Map.of("sa_a", Set.of(OLIST), "alice", Set.of(ORDERS));
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(sa("1", "sa_a", "pw00000000000000", Set.of(ORDERS))), actual, Map.of());

        assertThat(plans).singleElement().satisfies(p -> {
            assertThat(p.conflict()).isTrue();
            assertThat(p.statements()).isEmpty();
        });
    }

    @Test
    void refusesToBuildSqlFromUnsafeValues() {
        // Defence in depth for #22: StateService whitelists at the API boundary, the planner checks again
        DorisGrant evil = new DorisGrant("internal", "olist", "t'; DROP USER 'x", "SELECT_PRIV");
        assertThatThrownBy(() -> ReconcilePlanner.plan(List.of(sa("1", "sa_a", PW, Set.of(evil))), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReconcilePlanner.plan(List.of(sa("1", "sa'@'%'; --", PW, Set.of())), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReconcilePlanner.plan(List.of(sa("1", "sa_a", "pw' OR '1", Set.of())), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("OR '1");
        assertThatThrownBy(() -> ReconcilePlanner.plan(List.of(), Map.of("x'", Set.of()), Map.of("x'", managed("1", PW))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokesPrivilegesTheConsoleNeverGrants() {
        // Added by hand on Doris: not in the desired-state whitelist, but it must still be revocable
        DorisGrant handMade = new DorisGrant("internal", "olist", "*", "GRANT_PRIV");
        List<SaPlan> plans = ReconcilePlanner.plan(List.of(sa("1", "sa_a", PW, Set.of(ORDERS))),
                Map.of("sa_a", Set.of(ORDERS, handMade)), Map.of("sa_a", managed("1", PW)));

        assertThat(statements(plans)).containsExactly("REVOKE GRANT_PRIV ON internal.olist.* FROM 'sa_a'@'%'");
        // ...but a value from Doris that could break out of the statement is still refused
        DorisGrant broken = new DorisGrant("internal", "olist", "*", "SELECT_PRIV; DROP USER 'x'");
        assertThatThrownBy(() -> ReconcilePlanner.plan(List.of(sa("1", "sa_a", PW, Set.of(ORDERS))),
                Map.of("sa_a", Set.of(ORDERS, broken)), Map.of("sa_a", managed("1", PW))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String PW = "pw00000000000000";

    private static DesiredSa sa(String saId, String username, String password, Set<DorisGrant> grants) {
        return new DesiredSa(saId, username, password, grants);
    }

    private static ManagedSa managed(String saId, String password) {
        return new ManagedSa(saId, ReconcilePlanner.hash(password));
    }

    private static List<String> statements(List<SaPlan> plans) {
        return plans.stream().flatMap(p -> p.statements().stream()).map(Sql::sql).toList();
    }
}

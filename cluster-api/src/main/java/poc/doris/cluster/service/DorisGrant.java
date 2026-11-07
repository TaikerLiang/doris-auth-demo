package poc.doris.cluster.service;

/** One privilege on catalog.database.table; table "*" means the whole database. Privilege is upper case. */
public record DorisGrant(String catalog, String database, String table, String privilege) {

    public String target() {
        return catalog + "." + database + "." + table;
    }
}

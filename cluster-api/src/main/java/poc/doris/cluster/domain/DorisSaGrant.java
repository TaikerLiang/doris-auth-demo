package poc.doris.cluster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "doris_sa_grants")
public class DorisSaGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "char(36)")
    private String workspaceDorisId;
    private String catalogName;
    private String databaseName;
    private String tableName;
    private String privilege;

    protected DorisSaGrant() {
    }

    public DorisSaGrant(String workspaceDorisId, String catalogName, String databaseName, String tableName,
            String privilege) {
        this.workspaceDorisId = workspaceDorisId;
        this.catalogName = catalogName;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.privilege = privilege;
    }

    public String getWorkspaceDorisId() {
        return workspaceDorisId;
    }

    public String getCatalogName() {
        return catalogName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPrivilege() {
        return privilege;
    }
}

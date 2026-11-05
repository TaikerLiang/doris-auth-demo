package poc.doris.console.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisSaGrantRepository extends JpaRepository<DorisSaGrant, Long> {

    List<DorisSaGrant> findByWorkspaceDorisIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(
            String workspaceDorisId);

    long countByWorkspaceDorisId(String workspaceDorisId);

    boolean existsByWorkspaceDorisIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
            String workspaceDorisId, String catalogName, String databaseName, String tableName, String privilege);
}

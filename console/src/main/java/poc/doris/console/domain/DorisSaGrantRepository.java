package poc.doris.console.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisSaGrantRepository extends JpaRepository<DorisSaGrant, Long> {

    List<DorisSaGrant> findBySaIdOrderByCatalogNameAscDatabaseNameAscTableNameAscPrivilegeAsc(String saId);

    long countBySaId(String saId);

    boolean existsBySaIdAndCatalogNameAndDatabaseNameAndTableNameAndPrivilege(
            String saId, String catalogName, String databaseName, String tableName, String privilege);
}

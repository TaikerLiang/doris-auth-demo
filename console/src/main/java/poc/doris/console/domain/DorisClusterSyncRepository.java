package poc.doris.console.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DorisClusterSyncRepository extends JpaRepository<DorisClusterSync, String> {

    /** Row lock so concurrent changes in one fab get distinct, ordered versions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DorisClusterSync s where s.clusterName = :clusterName")
    Optional<DorisClusterSync> findForUpdate(String clusterName);

    List<DorisClusterSync> findAllByOrderByClusterName();
}

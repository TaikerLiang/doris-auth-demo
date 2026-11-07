package poc.doris.cluster.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DorisSyncStateRepository extends JpaRepository<DorisSyncState, String> {

    /** Row lock so two snapshots arriving together are accepted one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DorisSyncState s where s.clusterName = :clusterName")
    Optional<DorisSyncState> findForUpdate(String clusterName);
}

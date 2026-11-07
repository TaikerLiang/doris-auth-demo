package poc.doris.cluster.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisApplyOutboxRepository extends JpaRepository<DorisApplyOutbox, Long> {

    List<DorisApplyOutbox> findByStatusOrderById(String status);
}

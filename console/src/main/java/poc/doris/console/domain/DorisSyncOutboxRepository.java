package poc.doris.console.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisSyncOutboxRepository extends JpaRepository<DorisSyncOutbox, Long> {

    List<DorisSyncOutbox> findTop50ByOrderByIdDesc();
}

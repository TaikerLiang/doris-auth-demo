package poc.doris.cluster.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisManagedUserRepository extends JpaRepository<DorisManagedUser, Long> {

    List<DorisManagedUser> findByDorisHostId(String dorisHostId);

    Optional<DorisManagedUser> findByDorisHostIdAndUsername(String dorisHostId, String username);
}

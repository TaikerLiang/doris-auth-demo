package poc.doris.console.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisSaRepository extends JpaRepository<DorisSa, String> {

    List<DorisSa> findByWorkspaceDorisIdOrderByUsername(String workspaceDorisId);

    List<DorisSa> findByWorkspaceDorisIdIn(List<String> workspaceDorisIds);

    long countByWorkspaceDorisId(String workspaceDorisId);

    boolean existsByDorisHostIdAndUsername(String dorisHostId, String username);
}

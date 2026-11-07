package poc.doris.console.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceDorisRepository extends JpaRepository<WorkspaceDoris, String> {

    Optional<WorkspaceDoris> findByWorkspaceNameAndClusterName(String workspaceName, String clusterName);

    List<WorkspaceDoris> findByWorkspaceName(String workspaceName);

    List<WorkspaceDoris> findByClusterName(String clusterName);

    boolean existsByDorisHostId(String dorisHostId);

    boolean existsByDorisHostIdAndUsername(String dorisHostId, String username);
}

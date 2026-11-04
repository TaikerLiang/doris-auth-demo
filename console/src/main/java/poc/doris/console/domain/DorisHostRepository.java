package poc.doris.console.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DorisHostRepository extends JpaRepository<DorisHost, String> {

    List<DorisHost> findAllByOrderByClusterNameAscInstanceNameAsc();

    boolean existsByInstanceName(String instanceName);

    boolean existsByFeHostAndQueryPort(String feHost, int queryPort);
}

package poc.doris.console.scheduler;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code scheduler.jobs.<bean name>.{enabled, cron, zone}}; a job without zone uses default-zone. */
@ConfigurationProperties(prefix = "scheduler")
public record SchedulerProperties(String defaultZone, Map<String, Job> jobs) {

    public record Job(boolean enabled, String cron, String zone) {
    }
}

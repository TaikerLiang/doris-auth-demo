package poc.doris.cluster.scheduler;

import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Registers every {@link BaseScheduler} bean from the YAML entry named like the bean. A job that is disabled
 * or has no entry is not registered at all. Changing the YAML needs a restart.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties.class)
class SchedulerConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    private final Map<String, BaseScheduler> jobs;
    private final SchedulerProperties properties;

    SchedulerConfig(Map<String, BaseScheduler> jobs, SchedulerProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        jobs.forEach((name, job) -> {
            SchedulerProperties.Job config = properties.jobs() == null ? null : properties.jobs().get(name);
            if (config == null || !config.enabled()) {
                log.info("job={} disabled", name);
                return;
            }
            ZoneId zone = ZoneId.of(config.zone() != null ? config.zone() : properties.defaultZone());
            registrar.addCronTask(new CronTask(() -> job.execute(name), new CronTrigger(config.cron(), zone)));
            log.info("job={} scheduled cron=\"{}\" zone={}", name, config.cron(), zone);
        });
    }
}

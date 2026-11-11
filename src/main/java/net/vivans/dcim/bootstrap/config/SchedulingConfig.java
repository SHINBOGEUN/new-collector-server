package net.vivans.dcim.bootstrap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    @Bean
    /** 수집 cron과 MQTT 수신(LoRa 설정 갱신 등) @Scheduled 작업이 함께 쓰는 스케줄러. */
    public ThreadPoolTaskScheduler collectorTaskScheduler(
            @Value("${collector.scheduler.pool-size:10}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, poolSize));
        scheduler.setThreadNamePrefix("collector-cron-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}

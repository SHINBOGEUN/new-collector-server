package net.vivans.dcim.module.job.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CollectionMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;

    public CollectionMetrics(MeterRegistry meterRegistry) {
        this.successCounter = Counter.builder("collector.collection.device.success")
                .description("Successful SNMP device collections")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("collector.collection.device.failure")
                .description("Failed SNMP device collections")
                .register(meterRegistry);
    }

    public void recordSuccess() {
        successCounter.increment();
    }

    public void recordFailure() {
        failureCounter.increment();
    }
}

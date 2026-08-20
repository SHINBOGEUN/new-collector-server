package net.vivans.dcim.module.job.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionTickRunnerTest {

    @Test
    void publishesSuccessfulDeviceImmediatelyAndSkipsFailure() throws Exception {
        AtomicInteger snmpCalls = new AtomicInteger();
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> {
            snmpCalls.incrementAndGet();
            if ("bad".equals(host)) {
                throw new IllegalStateException("timeout");
            }
            return Map.of("V", 220.1);
        };
        AtomicInteger published = new AtomicInteger();
        MqttPublisher mqtt = (taskId, groupId, deviceId, values) -> published.incrementAndGet();
        CollectionTickRunner runner = new CollectionTickRunner(
                snmp,
                mqtt,
                new OidTemplateResolver(),
                new CollectionMetrics(new SimpleMeterRegistry())
        );

        CollectionGroupSpec spec = spec(
                List.of(
                        new CollectionGroupTargetSpec(1, "good", 161, null),
                        new CollectionGroupTargetSpec(2, "bad", 161, null)
                )
        );

        runner.run(spec, new AtomicBoolean(false));
        assertThat(snmpCalls.get()).isEqualTo(2);
        assertThat(published.get()).isEqualTo(1);
    }

    @Test
    void skipsOverlappingTick() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> {
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("stuck");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return Map.of("V", 1);
        };
        AtomicInteger published = new AtomicInteger();
        MqttPublisher mqtt = (taskId, groupId, deviceId, values) -> published.incrementAndGet();
        CollectionTickRunner runner = new CollectionTickRunner(
                snmp,
                mqtt,
                new OidTemplateResolver(),
                new CollectionMetrics(new SimpleMeterRegistry())
        );
        CollectionGroupSpec spec = spec(List.of(new CollectionGroupTargetSpec(1, "host", 161, null)));
        AtomicBoolean running = new AtomicBoolean(false);

        Thread first = new Thread(() -> runner.run(spec, running));
        first.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        runner.run(spec, running);
        release.countDown();
        first.join(3000);

        assertThat(published.get()).isEqualTo(1);
    }

    private CollectionGroupSpec spec(List<CollectionGroupTargetSpec> targets) {
        return new CollectionGroupSpec(
                1,
                11,
                10,
                "snmp",
                "0 */1 * * * *",
                "public",
                2000,
                1,
                10,
                List.of(new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.0", false)),
                targets,
                List.of()
        );
    }
}

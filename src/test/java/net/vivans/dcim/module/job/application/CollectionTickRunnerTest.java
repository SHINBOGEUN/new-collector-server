package net.vivans.dcim.module.job.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionPointSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
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

    @Test
    void livePublishesEachPointSeparately() throws Exception {
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> Map.of("V", 219, "W", 519);
        List<String> published = new java.util.ArrayList<>();
        CountDownLatch publishedLatch = new CountDownLatch(2);
        MqttPublisher mqtt = new MqttPublisher() {
            @Override
            public void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values) {
                throw new IllegalStateException("schedule publish must not be used for live");
            }

            @Override
            public void publishLivePoint(
                    int deviceId,
                    String displayName,
                    String pointName,
                    String unit,
                    Object value
            ) {
                synchronized (published) {
                    published.add(deviceId + ":" + pointName + "=" + value + ":" + displayName);
                }
                publishedLatch.countDown();
            }
        };
        CollectionTickRunner runner = new CollectionTickRunner(
                snmp,
                mqtt,
                new OidTemplateResolver(),
                new CollectionMetrics(new SimpleMeterRegistry())
        );

        runner.runLive(
                new LiveCollectionSpec(
                        1000,
                        "snmp",
                        "public",
                        2000,
                        1,
                        10,
                        List.of(new LiveCollectionTargetSpec(
                                9,
                                "PDU-1P-114",
                                "192.168.1.10",
                                161,
                                null,
                                List.of(
                                        new LiveCollectionPointSpec("V", "1.3.6.1.4.1.6375.1.1.0", false, "V"),
                                        new LiveCollectionPointSpec("W", "1.3.6.1.4.1.6375.1.8.0", false, "W")
                                )
                        ))
                ),
                new AtomicBoolean(false)
        );

        assertThat(publishedLatch.await(2, TimeUnit.SECONDS)).isTrue();
        synchronized (published) {
            assertThat(published).containsExactly("9:V=219:PDU-1P-114", "9:W=519:PDU-1P-114");
        }
    }

    @Test
    void liveTickReturnsWithoutWaitingForSlowDevice() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch fastPublished = new CountDownLatch(1);
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> {
            if ("slow".equals(host)) {
                slowStarted.countDown();
                try {
                    if (!slowRelease.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("stuck");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return Map.of("W", 1);
            }
            return Map.of("W", 519);
        };
        MqttPublisher mqtt = new MqttPublisher() {
            @Override
            public void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values) {
            }

            @Override
            public void publishLivePoint(
                    int deviceId,
                    String displayName,
                    String pointName,
                    String unit,
                    Object value
            ) {
                if (deviceId == 11) {
                    fastPublished.countDown();
                }
            }
        };
        CollectionTickRunner runner = new CollectionTickRunner(
                snmp,
                mqtt,
                new OidTemplateResolver(),
                new CollectionMetrics(new SimpleMeterRegistry())
        );

        long startedAt = System.nanoTime();
        runner.runLive(
                new LiveCollectionSpec(
                        1000,
                        "snmp",
                        "public",
                        2000,
                        1,
                        10,
                        List.of(
                                new LiveCollectionTargetSpec(
                                        1,
                                        "slow-pdu",
                                        "slow",
                                        161,
                                        null,
                                        List.of(new LiveCollectionPointSpec("W", "1.3.6.1.4.1.6375.1.8.0", false, "W"))
                                ),
                                new LiveCollectionTargetSpec(
                                        11,
                                        "fast-pdu",
                                        "fast",
                                        161,
                                        null,
                                        List.of(new LiveCollectionPointSpec("W", "1.3.6.1.4.1.6375.1.8.0", false, "W"))
                                )
                        )
                ),
                new AtomicBoolean(false)
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs).isLessThan(500);
        assertThat(fastPublished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
        slowRelease.countDown();
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

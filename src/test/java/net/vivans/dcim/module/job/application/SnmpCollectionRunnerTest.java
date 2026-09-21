package net.vivans.dcim.module.job.application;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SnmpCollectionRunnerTest {
    private final SnmpQueryClient snmp = mock(SnmpQueryClient.class);
    private final MqttPublisher mqtt = mock(MqttPublisher.class);
    private final CollectionMetrics metrics = mock(CollectionMetrics.class);
    private final SnmpCollectionRunner runner = new SnmpCollectionRunner(snmp, mqtt, new OidTemplateResolver(), metrics);
    private final CollectionGroupTargetSpec target = new CollectionGroupTargetSpec(14, "host", 161, 3);

    @Test
    void regularResolvesOidScalesAndPublishes() throws Exception {
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of("W", 12));
        var result = runner.collectTarget(spec(), target);
        assertThat(result).isEqualTo(new CollectionTargetResult(true, null));
        verify(snmp).get("host", 161, "public", 2000, 1,
                List.of(new SnmpQueryClient.OidQuery("W", "1.3.6.3.0")));
        verify(mqtt).publishSensorReading(1, 11, 14, Map.of("W", 12000.0));
        verify(metrics).recordSuccess();
        verify(metrics, never()).recordFailure();
    }

    @Test
    void snmpFailureReturnsReasonWithoutPublishing() throws Exception {
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenThrow(new IllegalStateException("timeout"));
        var result = runner.collectTarget(spec(), target);
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("deviceId=14", "host=host:161", "timeout");
        verifyNoInteractions(mqtt);
        verify(metrics).recordFailure();
        verify(metrics, never()).recordSuccess();
    }

    @Test
    void missingInstanceFailsBeforeNetworkRequest() {
        var result = runner.collectTarget(spec(), new CollectionGroupTargetSpec(14, "host", 161, null));
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("instanceId");
        verifyNoInteractions(snmp, mqtt);
        verify(metrics).recordFailure();
    }

    @Test
    void mqttFailureRemainsCollectionFailure() throws Exception {
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of("W", 1));
        doThrow(new IllegalStateException("publish failed")).when(mqtt)
                .publishSensorReading(anyInt(), anyInt(), anyInt(), anyMap());
        assertThat(runner.collectTarget(spec(), target).reason()).contains("publish failed");
        verify(metrics).recordFailure();
        verify(metrics, never()).recordSuccess();
    }

    @Test
    void liveScalesPublishesAvailablePointsAndSkipsMissingValues() throws Exception {
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of("W", 12));
        var liveTarget = new LiveCollectionTargetSpec(14, "PDU", "host", 161, 3, List.of(
                new LiveCollectionPointSpec("W", "1.3.6.{instanceId}.0", true, 1000.0, "W"),
                new LiveCollectionPointSpec("TEMP", "1.3.6.2.0", false, null, "C")));
        assertThat(runner.collectLiveTarget(liveSpec(), liveTarget)).isTrue();
        verify(mqtt).publishLivePoint(14, "PDU", "W", "W", 12000.0);
        verifyNoMoreInteractions(mqtt);
        verify(metrics).recordSuccess();
    }

    @Test
    void liveEmptyPointsDoNotReadOrPublish() {
        var liveTarget = new LiveCollectionTargetSpec(14, "PDU", "host", 161, 3, List.of());
        assertThat(runner.collectLiveTarget(liveSpec(), liveTarget)).isFalse();
        verifyNoInteractions(snmp, mqtt, metrics);
    }

    @Test
    void liveFailureRecordsFailureWithoutPublishing() throws Exception {
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenThrow(new IllegalStateException("timeout"));
        var liveTarget = new LiveCollectionTargetSpec(14, "PDU", "host", 161, 3,
                List.of(new LiveCollectionPointSpec("W", "1.3.6.1.0", false, null, "W")));
        assertThat(runner.collectLiveTarget(liveSpec(), liveTarget)).isFalse();
        verifyNoInteractions(mqtt);
        verify(metrics).recordFailure();
    }

    private CollectionGroupSpec spec() {
        return new CollectionGroupSpec(1, 11, 4, "snmp", "0 * * * * *", "public", 2000, 1, 10,
                List.of(new CollectionGroupOidSpec("W", "1.3.6.{instanceId}.0", true, 1000.0)),
                List.of(target), List.of());
    }

    private LiveCollectionSpec liveSpec() {
        return new LiveCollectionSpec(1000, "snmp", "public", 2000, 1, 10, List.of());
    }
}

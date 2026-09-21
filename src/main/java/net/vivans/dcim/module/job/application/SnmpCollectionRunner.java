package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionPointSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SNMP 장비 한 대의 정기·실시간 수집과 결과 발행을 담당합니다. */
@Slf4j
@Component
public class SnmpCollectionRunner {
    private final SnmpQueryClient snmpQueryClient;
    private final MqttPublisher mqttPublisher;
    private final OidTemplateResolver oidTemplateResolver;
    private final CollectionMetrics collectionMetrics;

    public SnmpCollectionRunner(
            SnmpQueryClient snmpQueryClient,
            MqttPublisher mqttPublisher,
            OidTemplateResolver oidTemplateResolver,
            CollectionMetrics collectionMetrics
    ) {
        this.snmpQueryClient = snmpQueryClient;
        this.mqttPublisher = mqttPublisher;
        this.oidTemplateResolver = oidTemplateResolver;
        this.collectionMetrics = collectionMetrics;
    }

    public CollectionTargetResult collectTarget(CollectionGroupSpec spec, CollectionGroupTargetSpec target) {
        try {
            List<SnmpQueryClient.OidQuery> queries = new ArrayList<>();
            for (CollectionGroupOidSpec oid : spec.oids() == null ? List.<CollectionGroupOidSpec>of() : spec.oids()) {
                queries.add(new SnmpQueryClient.OidQuery(oid.name(), oidTemplateResolver.resolve(oid, target)));
            }
            Map<String, Object> values = snmpQueryClient.get(
                    target.host(),
                    target.port(),
                    spec.community(),
                    spec.timeoutMs(),
                    spec.retries(),
                    queries
            );
            Map<String, Object> scaled = ScaledValues.apply(values, spec.oids());
            mqttPublisher.publishSensorReading(spec.taskId(), spec.groupId(), target.deviceId(), scaled);
            collectionMetrics.recordSuccess();
            return new CollectionTargetResult(true, null);
        } catch (Exception ex) {
            collectionMetrics.recordFailure();
            String reason = "deviceId=" + target.deviceId() + " host=" + target.host() + ":" + target.port()
                    + " " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn(
                    "수집 실패 taskId={} groupId={} deviceId={} host={}:{} reason={}",
                    spec.taskId(),
                    spec.groupId(),
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    ex.getMessage()
            );
            return new CollectionTargetResult(false, reason);
        }
    }

    public boolean collectLiveTarget(LiveCollectionSpec spec, LiveCollectionTargetSpec target) {
        try {
            List<LiveCollectionPointSpec> points = target.points() == null ? List.of() : target.points();
            if (points.isEmpty()) {
                return false;
            }
            CollectionGroupTargetSpec resolveTarget = new CollectionGroupTargetSpec(
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    target.instanceId()
            );
            List<SnmpQueryClient.OidQuery> queries = new ArrayList<>();
            for (LiveCollectionPointSpec point : points) {
                CollectionGroupOidSpec oid = new CollectionGroupOidSpec(
                        point.name(),
                        point.template(),
                        point.requiresInstance(),
                        point.scale()
                );
                queries.add(new SnmpQueryClient.OidQuery(point.name(), oidTemplateResolver.resolve(oid, resolveTarget)));
            }
            Map<String, Object> values = snmpQueryClient.get(
                    target.host(),
                    target.port(),
                    spec.community(),
                    spec.timeoutMs(),
                    spec.retries(),
                    queries
            );
            Map<String, Object> scaled = ScaledValues.applyLive(values, points);
            for (LiveCollectionPointSpec point : points) {
                Object value = scaled.get(point.name());
                if (value == null) {
                    continue;
                }
                mqttPublisher.publishLivePoint(
                        target.deviceId(),
                        target.deviceName(),
                        point.name(),
                        point.unit(),
                        value
                );
            }
            collectionMetrics.recordSuccess();
            return true;
        } catch (Exception ex) {
            collectionMetrics.recordFailure();
            log.warn(
                    "live 수집 실패 deviceId={} host={}:{} reason={}",
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    ex.getMessage()
            );
            return false;
        }
    }
}

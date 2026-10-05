package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusPointResolver;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveModbusTargetSpec;
import net.vivans.dcim.module.modbus.ModbusPartialReadException;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 저장 대상 장비 하나의 Modbus 값을 읽고 기존 정기 수집 MQTT 토픽으로 발행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModbusCollectionRunner {

    private final ModbusQueryClient modbusQueryClient;
    private final MqttPublisher mqttPublisher;
    private final CollectionMetrics collectionMetrics;

    /** Live reads use the same decoder and bit-field rules, but never publish to the scheduled-data topic. */
    public boolean collectLiveTarget(LiveCollectionSpec spec, LiveModbusTargetSpec liveTarget) {
        ModbusCollectionTargetSpec target = liveTarget.target();
        List<CollectionGroupModbusPointSpec> points = target.points() == null ? List.of() : target.points();
        if (points.isEmpty()) return false;
        try {
            Map<String, Object> values;
            ModbusPartialReadException partialFailure = null;
            try {
                values = modbusQueryClient.read(target, points, spec.timeoutMs(), spec.retries());
            } catch (ModbusPartialReadException exception) {
                values = exception.values();
                partialFailure = exception;
            }
            Map<String, String> units = liveTarget.units() == null ? Map.of() : liveTarget.units();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (!units.containsKey(entry.getKey()) || entry.getValue() == null) continue;
                mqttPublisher.publishLivePoint(target.deviceId(), liveTarget.deviceName(), entry.getKey(),
                        units.get(entry.getKey()), entry.getValue(), "modbus", liveTarget.sourceDeviceId());
            }
            if (partialFailure != null) {
                collectionMetrics.recordFailure();
                log.warn("[MODBUS_LIVE_PARTIAL] deviceId={} unitId={} reason={}",
                        target.deviceId(), target.unitId(), partialFailure.getMessage());
                return false;
            }
            collectionMetrics.recordSuccess();
            return true;
        } catch (Exception exception) {
            collectionMetrics.recordFailure();
            log.warn("[MODBUS_LIVE_ERROR] deviceId={} host={}:{} unitId={} reason={}",
                    target.deviceId(), target.host(), target.port(), target.unitId(), exception.getMessage());
            return false;
        }
    }

    public CollectionTargetResult collectTarget(ModbusCollectionGroupSpec spec, ModbusCollectionTargetSpec target) {
        List<CollectionGroupModbusPointSpec> points = ModbusPointResolver.resolve(spec, target);
        if (points.isEmpty()) {
            return new CollectionTargetResult(false, "deviceId=" + target.deviceId() + " has no Modbus points");
        }
        try {
            ModbusPartialReadException partialFailure = null;
            Map<String, Object> values;
            try {
                values = modbusQueryClient.read(target, points, spec.timeoutMs(), spec.retries());
            } catch (ModbusPartialReadException exception) {
                partialFailure = exception;
                values = exception.values();
            }
            if (!values.isEmpty()) {
                mqttPublisher.publishSensorReading(spec.taskId(), spec.groupId(), target.deviceId(), values, "modbus");
            }
            if (partialFailure != null) {
                collectionMetrics.recordFailure();
                String reason = "deviceId=" + target.deviceId() + " host=" + target.host() + ":" + target.port()
                        + " unitId=" + target.unitId() + " " + partialFailure.getMessage();
                log.warn("[MODBUS_COLLECT_PARTIAL] taskId={} groupId={} publishedPoints={} {}",
                        spec.taskId(), spec.groupId(), values.size(), reason);
                return new CollectionTargetResult(false, reason);
            }
            collectionMetrics.recordSuccess();
            return new CollectionTargetResult(true, null);
        } catch (Exception exception) {
            collectionMetrics.recordFailure();
            String reason = "deviceId=" + target.deviceId() + " host=" + target.host() + ":" + target.port()
                    + " unitId=" + target.unitId() + " "
                    + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            log.warn("[MODBUS_COLLECT_ERROR] taskId={} groupId={} {}", spec.taskId(), spec.groupId(), reason);
            return new CollectionTargetResult(false, reason);
        }
    }
}

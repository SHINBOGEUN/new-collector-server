package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusPointResolver;
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

    public CollectionTargetResult collectTarget(ModbusCollectionGroupSpec spec, ModbusCollectionTargetSpec target) {
        List<CollectionGroupModbusPointSpec> points = ModbusPointResolver.resolve(spec, target);
        if (points.isEmpty()) {
            return new CollectionTargetResult(false, "deviceId=" + target.deviceId() + " has no Modbus points");
        }
        try {
            Map<String, Object> values = modbusQueryClient.read(target, points, spec.timeoutMs(), spec.retries());
            mqttPublisher.publishSensorReading(spec.taskId(), spec.groupId(), target.deviceId(), values, "modbus");
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

package net.vivans.dcim.module.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "collector.mqtt.enabled", havingValue = "false", matchIfMissing = false)
public class NoOpMqttPublisher implements MqttPublisher {

    @Override
    public void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values) {
        log.debug("MQTT 비활성, skip device:{}", deviceId);
    }
}

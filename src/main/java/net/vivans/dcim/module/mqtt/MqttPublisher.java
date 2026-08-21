package net.vivans.dcim.module.mqtt;

import java.util.Map;

public interface MqttPublisher {

    void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values);

    default void publishLivePoint(
            int deviceId,
            String displayName,
            String pointName,
            String unit,
            Object value
    ) {
    }
}


package net.vivans.dcim.module.mqtt;

import java.util.Map;

public interface MqttPublisher {

    void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values);

    default void publishSensorReading(int taskId, int groupId, int deviceId,
                                      Map<String, Object> values, String protocol) {
        if (!"snmp".equals(protocol)) {
            throw new UnsupportedOperationException("MQTT publisher does not support protocol=" + protocol);
        }
        publishSensorReading(taskId, groupId, deviceId, values);
    }

    default void publishPueReading(int pueDefinitionId, int configVersion, double value, double totalPower, double coolerPower) {
    }

    default void publishLivePoint(
            int deviceId,
            String displayName,
            String pointName,
            String unit,
            Object value
    ) {
    }
}


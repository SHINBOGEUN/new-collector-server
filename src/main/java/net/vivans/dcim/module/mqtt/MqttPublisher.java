package net.vivans.dcim.module.mqtt;

import java.util.Map;

public interface MqttPublisher {

    void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values);
}

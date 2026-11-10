package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement;
import java.time.Instant;
import java.util.List;

final class MqttInfluxPointMapper {
    private MqttInfluxPointMapper() {}

    static List<Point> toPoints(String measurement, int deviceId, Integer modelId,
                               ManagerDeviceResponse device, List<MqttMeasurement> values, Instant time) {
        return values.stream().filter(value -> Double.isFinite(value.value())).map(value -> {
            Point point = Point.measurement(measurement).addTag("device_id", String.valueOf(deviceId))
                    .addTag("point_name", value.pointName()).addTag("protocol", "mqtt")
                    .addField("value", value.value()).time(time, WritePrecision.NS);
            if (modelId != null) point.addTag("model_id", modelId.toString());
            if (device != null) {
                tag(point, "device_type", device.deviceTypeCode());
                tag(point, "location_code", device.locationNodeCode());
            }
            tag(point, "component_type", value.componentType());
            tag(point, "component_index", value.componentIndex());
            tag(point, "component_uuid", value.componentUuid());
            return point;
        }).toList();
    }

    private static void tag(Point point, String name, String value) {
        if (value != null && !value.isBlank()) point.addTag(name, value);
    }
}

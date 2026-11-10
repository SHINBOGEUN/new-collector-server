package net.vivans.dcim.module.ingest.mqtt.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ingest.mqtt")
public class SensorMqttProperties {

    private boolean enabled = true;
    private String brokerUrl;
    private String clientId;
    private String topic = "dcim/sensor/data";
    private String calculatedTopic = "dcim/derived/calculated";
    private long configTtlMs = 60000;
    private String username = "";
    private String password = "";
}

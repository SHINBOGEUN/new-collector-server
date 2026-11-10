package net.vivans.dcim.module.ingest.influx.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "influx")
public class InfluxProperties {

    private boolean enabled;
    private String url;
    private String token = "";
    private String org = "vivans";
    private String bucket = "dcim";
    private String measurement = "dcim_sensor";
}

package net.vivans.dcim.module.ingest.manager.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "manager.service")
public class ManagerServiceProperties {

    private String url;
    private String apiKey = "";
    private boolean enabled = true;
}

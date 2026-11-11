package net.vivans.dcim.bootstrap.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Value("${app.version:unknown}")
    private String projectVersion;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DCIM DEFOG SHOW ROOM - COLLECTOR SERVER")
                        .version(projectVersion)
                        .description("SNMP/Modbus collection jobs and MQTT ingest to InfluxDB. Authenticate with X-Api-Key."))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")));
    }
}

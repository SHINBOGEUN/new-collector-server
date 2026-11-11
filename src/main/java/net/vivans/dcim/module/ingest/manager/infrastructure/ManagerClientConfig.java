package net.vivans.dcim.module.ingest.manager.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ManagerServiceProperties.class)
public class ManagerClientConfig {

    @Bean
    public RestClient managerRestClient(ManagerServiceProperties properties) {
        return RestClient.builder()
                .requestFactory(managerRequestFactory())
                .baseUrl(properties.getUrl())
                .defaultHeader("X-Api-Key", properties.getApiKey())
                .build();
    }

    private org.springframework.http.client.JdkClientHttpRequestFactory managerRequestFactory() {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        return factory;
    }
}

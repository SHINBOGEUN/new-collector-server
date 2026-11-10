package net.vivans.dcim.module.ingest.mqtt.api;

import net.vivans.dcim.module.ingest.mqtt.application.MqttConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = net.vivans.dcim.bootstrap.CollectorServerApplication.class,
        properties = {"security.auth-enabled=true", "security.api-key=test-only-key",
        "ingest.mqtt.enabled=false", "manager.service.enabled=false", "influx.enabled=false"})
@AutoConfigureMockMvc
class MqttConfigControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean(name = "managerRestClient") org.springframework.web.client.RestClient managerRestClient;
    @MockitoBean net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient alarmMeasurementClient;
    @MockitoBean net.vivans.dcim.module.ingest.manager.infrastructure.GpuAgentDiscoveryClient gpuAgentDiscoveryClient;
    @MockitoBean net.vivans.dcim.module.snmp.SnmpQueryClient snmpQueryClient;
    @MockitoBean MqttConfigurationService configuration;
    @MockitoBean net.vivans.dcim.module.ingest.lora.application.LoraConfigCache loraCache;
    @MockitoBean net.vivans.dcim.module.ingest.mqtt.infrastructure.LoraMqttSubscriber loraSubscriber;

    @Test
    void refreshRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/internal/mqtt/config/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/lora/config/refresh").header("X-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(configuration);
    }

    @Test
    void commonAndLegacyRefreshUrlsBothRefreshCommonConfiguration() throws Exception {
        mvc.perform(post("/api/internal/mqtt/config/refresh").header("X-Api-Key", "test-only-key"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/internal/lora/config/refresh").header("X-Api-Key", "test-only-key"))
                .andExpect(status().isNoContent());
        verify(configuration, times(2)).refresh();
        verify(loraCache).refresh();
        verify(loraSubscriber).requestRefresh();
    }

    @Test
    void commonAndLegacySourceRefreshUrlsReconcileManagerSources() throws Exception {
        mvc.perform(post("/api/internal/mqtt/sources/refresh").header("X-Api-Key", "test-only-key"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/internal/lora/sources/refresh").header("X-Api-Key", "test-only-key"))
                .andExpect(status().isNoContent());
        verify(loraSubscriber, times(2)).requestRefresh();
        mvc.perform(post("/api/internal/mqtt/sources/refresh")).andExpect(status().isUnauthorized());
    }
}

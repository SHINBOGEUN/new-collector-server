package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.influx.application.InfluxWriteService;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerDeviceClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.GpuAgentDiscoveryClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.MqttIdentityResponse;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentMqttMessageHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MqttConfigurationService config = mock(MqttConfigurationService.class);
    private final ManagerDeviceClient devices = mock(ManagerDeviceClient.class);
    private final InfluxWriteService writer = mock(InfluxWriteService.class);
    private final GpuAgentDiscoveryClient discovery = mock(GpuAgentDiscoveryClient.class);
    private final AgentMqttMessageHandler handler = new AgentMqttMessageHandler(config, new MqttPointExtractor(mapper), devices, writer, discovery);

    @Test
    void unknownUuidIsSubmittedForApprovalWithoutWritingMeasurements() throws Exception {
        String uuid = "d2d86cc5-224d-4b97-964b-abd9ac94765c";
        when(config.resolve("UUID", uuid)).thenReturn(Optional.empty());
        handler.handle(mapper.readTree("""
            {"time":"2026-10-14T08:11:13Z","deviceInfo":{"identityType":"UUID",
            "externalId":"d2d86cc5-224d-4b97-964b-abd9ac94765c","deviceName":"server"},
            "metadata":{"os":"Linux"},"components":[]}
            """));
        verify(discovery).discover(eq(uuid), eq("server"), any());
        verifyNoInteractions(writer);
    }

    @Test
    void confirmedAgentJsonResolvesMacAndWritesConfiguredDeviceAndGpuValues() throws Exception {
        var identity = new MqttIdentityResponse(12, "MAC", "aabbccddeeff", "AABBCCDDEEFF", 40, "GPU1", 8, "GPU", true);
        when(config.resolve("MAC", "aabbccddeeff")).thenReturn(Optional.of(new MqttConfigurationService.ResolvedConfiguration(identity,
                List.of(MqttPointExtractorTest.point("load_avg_1min", "object.load_avg_1min", false, null, null),
                        MqttPointExtractorTest.point("gpu_temperature", "object.gpu_temperature", true, null, null)))));
        when(devices.findDevice(40)).thenReturn(Optional.empty());
        handler.handle(mapper.readTree("""
                {"time":"2026-10-14T17:11:13.545533+09:00",
                 "deviceInfo":{"identityType":"MAC","externalId":"aabbccddeeff"},
                 "object":{"load_avg_1min":0.01},"metadata":{"cpu":"CPU","secret":"not a measurement"},
                 "components":[{"type":"GPU","index":0,"uuid":"GPU-AAA","object":{"gpu_temperature":59,"power":249.8}}]}
                """));
        verify(writer).writeMqttPoints(40, 8, null, List.of(
                new MqttMeasurement("load_avg_1min", 0.01, null, null, null),
                new MqttMeasurement("gpu_temperature", 59, "GPU", "0", "GPU-AAA")),
                Instant.parse("2026-10-14T08:11:13.545533Z"));
    }

    @Test
    void draginoPayloadIsNotProcessedByAgentHandler() throws Exception {
        assertThatThrownBy(() -> handler.handle(mapper.readTree("""
                {"time":"2026-10-14T08:11:13Z","deviceInfo":{"devEui":"a8404124875d9d36"},"object":{"TempC_SHT":23.89}}
                """))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(config, writer);
    }

    @Test
    void invalidTimeOrUnregisteredDeviceNeverWrites() throws Exception {
        assertThatThrownBy(() -> handler.handle(mapper.readTree("{\"time\":\"invalid\"}")))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
        when(config.resolve("MAC", "aabbccddeeff")).thenReturn(Optional.empty());
        handler.handle(mapper.readTree("""
                {"time":"2026-10-14T08:11:13Z","deviceInfo":{"identityType":"MAC","externalId":"aabbccddeeff"}}
                """));
        verifyNoInteractions(writer);
    }
}


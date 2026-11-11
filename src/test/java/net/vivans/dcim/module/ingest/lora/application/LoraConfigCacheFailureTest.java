package net.vivans.dcim.module.ingest.lora.application;

import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import net.vivans.dcim.module.ingest.lora.infrastructure.ManagerLoraConfigClient;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraEndpointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraModelPointResponse;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.mqtt.config.LoraMqttProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manager 조회 실패를 "설정 없음"으로 읽으면 정상 수집까지 끊긴다.
 * 실패 시 직전 설정을 유지하는지 검증한다.
 */
class LoraConfigCacheFailureTest {

    private ManagerLoraConfigClient client;
    private LoraConfigCache cache;

    @BeforeEach
    void setUp() {
        client = mock(ManagerLoraConfigClient.class);
        cache = new LoraConfigCache(client, new LoraMqttProperties());
    }

    @Test
    void modelMappingCanBeResolvedWithoutDeviceOverride() {
        when(client.findAllEnabledModelMappings()).thenReturn(List.of(
                new LoraModelPointResponse(1, 9, "object.TempC_SHT", "TEMPERATURE",
                        1, "TEMPERATURE", "°C", null, null, true)));
        cache.refresh();

        assertThat(cache.resolveMapping(9, "object.TempC_SHT"))
                .get().extracting(LoraMappingRule::pointName).isEqualTo("TEMPERATURE");
    }

    @Test
    void firstRefreshFailureLeavesCacheUnloadedInsteadOfReportingEmptyConfig() {
        when(client.countEnabledIdentities())
                .thenThrow(new ManagerConfigUnavailableException("identities", new IllegalStateException("404")));

        cache.refresh();

        assertThat(cache.isLoaded()).isFalse();
        assertThat(cache.hasAnyIdentity()).isFalse();
    }

    @Test
    void laterFailureKeepsLastSuccessfulConfig() {
        when(client.countEnabledIdentities()).thenReturn(2);
        when(client.findAllEnabledEndpoints()).thenReturn(List.of(endpoint()));
        when(client.findAllEnabledModelMappings()).thenReturn(List.of());
        when(client.findAllEnabledOverrides()).thenReturn(List.of());
        cache.refresh();
        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.hasAnyIdentity()).isTrue();

        when(client.countEnabledIdentities())
                .thenThrow(new ManagerConfigUnavailableException("identities", new IllegalStateException("500")));
        cache.refresh();

        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.hasAnyIdentity()).isTrue();
        assertThat(cache.hasConfiguredEndpoint()).isTrue();
    }

    @Test
    void emptyConfigIsDistinguishedFromFetchFailure() {
        when(client.countEnabledIdentities()).thenReturn(0);
        when(client.findAllEnabledEndpoints()).thenReturn(List.of());
        when(client.findAllEnabledModelMappings()).thenReturn(List.of());
        when(client.findAllEnabledOverrides()).thenReturn(List.of());

        cache.refresh();

        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.hasAnyIdentity()).isFalse();
    }

    @Test
    void identityCountIncludesNonLoraTypesSoAgentOnlyDeploymentStartsCollecting() {
        // MAC만 등록된 환경: LoRa 매핑은 비어 있지만 수집은 시작되어야 한다.
        when(client.countEnabledIdentities()).thenReturn(1);
        when(client.findAllEnabledEndpoints()).thenReturn(List.of());
        when(client.findAllEnabledModelMappings()).thenReturn(List.of());
        when(client.findAllEnabledOverrides()).thenReturn(List.of());

        cache.refresh();

        assertThat(cache.hasConfiguredEndpoint()).isFalse();
        assertThat(cache.hasAnyIdentity()).isTrue();
    }

    @Test
    void partialFailureDoesNotPublishHalfUpdatedConfig() {
        when(client.countEnabledIdentities()).thenReturn(2);
        when(client.findAllEnabledEndpoints()).thenReturn(List.of(endpoint()));
        when(client.findAllEnabledModelMappings()).thenReturn(List.of());
        when(client.findAllEnabledOverrides()).thenReturn(List.of());
        cache.refresh();

        when(client.findAllEnabledEndpoints()).thenReturn(List.of());
        when(client.findAllEnabledModelMappings())
                .thenThrow(new ManagerConfigUnavailableException("model-mappings", new IllegalStateException("500")));
        cache.refresh();

        assertThat(cache.hasConfiguredEndpoint()).isTrue();
        assertThat(cache.resolveDevice(LoraIdType.DEV_EUI, "A8404128115A6092")).isPresent();
    }

    private static LoraEndpointResponse endpoint() {
        return new LoraEndpointResponse(1, 20, "무선 온습도 센서", 9, "LHT65",
                LoraIdType.DEV_EUI, "a8404128115a6092", "A8404128115A6092", true);
    }
}

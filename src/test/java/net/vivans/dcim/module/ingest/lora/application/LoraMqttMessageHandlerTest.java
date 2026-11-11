package net.vivans.dcim.module.ingest.lora.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.influx.application.InfluxWriteService;
import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerDeviceClient;
import net.vivans.dcim.module.ingest.mqtt.config.LoraMqttProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장비 식별 우선순위(요청 기준):
 * 1) 설정된 devEUI 경로에서 값 추출 성공 시 devEUI로 매칭
 * 2) devEUI가 payload에 없으면 deviceInfo.deviceName으로 매칭 (레거시 운영 호환)
 * 3) 둘 다 없거나 매칭(등록 조회)에 실패하면 로그만 남기고 종료 — devEUI 매칭이
 *    실패했다고 deviceName으로 다시 시도하지는 않는다(레지스트리 조회 실패와 "값 없음"은 다르게 취급).
 *
 * 영구 오류 이력(lora_ingest_error_log)이 제거되어, 이 테스트는 Mock이 아닌 실제
 * {@link LoraRuntimeStatusCache} 인스턴스를 주입해 handle() 호출 후 캐시 스냅샷으로 검증한다.
 */
class LoraMqttMessageHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LoraMqttProperties properties;
    private LoraConfigCache configCache;
    private LoraValueConverter valueConverter;
    private ManagerDeviceClient managerDeviceClient;
    private LoraRuntimeStatusCache runtimeStatusCache;
    private InfluxWriteService influxWriteService;
    private LoraMqttMessageHandler handler;

    @BeforeEach
    void setUp() {
        properties = new LoraMqttProperties();
        properties.setDevEuiPath("deviceInfo.devEui");
        properties.setDeviceNamePath("deviceInfo.deviceName");

        configCache = mock(LoraConfigCache.class);
        valueConverter = new LoraValueConverter(objectMapper);
        managerDeviceClient = mock(ManagerDeviceClient.class);
        runtimeStatusCache = new LoraRuntimeStatusCache();
        influxWriteService = mock(InfluxWriteService.class);

        handler = new LoraMqttMessageHandler(
                objectMapper, properties, configCache, valueConverter,
                managerDeviceClient, runtimeStatusCache, influxWriteService);
    }

    private LoraResolvedDevice device(int id, int modelId) {
        return new LoraResolvedDevice(id, "dev-" + id, modelId, "model-" + modelId);
    }

    @Test
    void devEuiPresent_matchesByDevEui() {
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"24E124710C123456\",\"deviceName\":\"unused-name\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(9, 10);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("24E124710C123456")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(10)).thenReturn(Set.of("object.TempC_SHT"));
        when(configCache.resolveMapping(10, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));
        when(managerDeviceClient.findDevice(9)).thenReturn(Optional.empty());

        handler.handle("application/dev1/event/up", payload);

        verify(configCache).resolveDevice(LoraIdType.DEV_EUI, "24E124710C123456");
        verify(configCache, never()).resolveDevice(eq(LoraIdType.DEVICE_NAME), any());

        ArgumentCaptor<Map<String, Object>> valuesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(influxWriteService).writeSensorPoints(eq(9), any(), valuesCaptor.capture(), any(), isNull(), eq("mqtt"));
        assertThat(valuesCaptor.getValue()).containsEntry("TEMPERATURE", 23.5);

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(9);
        assertThat(snapshot.lastReceivedAt()).isNotNull();
        assertThat(snapshot.lastSavedAt()).isNotNull();
        assertThat(snapshot.lastPointCount()).isEqualTo(1);
        assertThat(snapshot.lastErrorCode()).isNull();
    }

    @Test
    void devEuiAbsent_fallsBackToDeviceName() {
        byte[] payload = ("{\"deviceInfo\":{\"deviceName\":\"dragino-lht65n-01\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(11, 12);
        when(configCache.resolveDevice(eq(LoraIdType.DEVICE_NAME), eq("dragino-lht65n-01")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(12)).thenReturn(Set.of("object.TempC_SHT"));
        when(configCache.resolveMapping(12, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));
        when(managerDeviceClient.findDevice(11)).thenReturn(Optional.empty());

        handler.handle("application/dev2/event/up", payload);

        verify(configCache, never()).resolveDevice(eq(LoraIdType.DEV_EUI), any());
        verify(configCache).resolveDevice(LoraIdType.DEVICE_NAME, "dragino-lht65n-01");
        verify(influxWriteService).writeSensorPoints(eq(11), any(), any(), any(), isNull(), eq("mqtt"));
        assertThat(runtimeStatusCache.snapshotOf(11).lastSavedAt()).isNotNull();
    }

    @Test
    void devEuiBlank_treatedAsAbsentAndFallsBackToDeviceName_andNoMappingRecordsError() {
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"\",\"deviceName\":\"dragino-lht65n-02\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(13, 14);
        when(configCache.resolveDevice(eq(LoraIdType.DEVICE_NAME), eq("dragino-lht65n-02")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(14)).thenReturn(Set.of());

        handler.handle("application/dev3/event/up", payload);

        verify(configCache).resolveDevice(LoraIdType.DEVICE_NAME, "dragino-lht65n-02");
        verify(configCache, never()).resolveDevice(eq(LoraIdType.DEV_EUI), any());

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(13);
        assertThat(snapshot.lastReceivedAt()).isNotNull();
        assertThat(snapshot.lastErrorCode()).isEqualTo("NO_MAPPING");
        assertThat(snapshot.lastSavedAt()).isNull();
        verify(influxWriteService, never()).writeSensorPoints(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void neitherDevEuiNorDeviceNamePresent_doesNotCallConfigCacheOrTouchCache() {
        byte[] payload = "{\"deviceInfo\":{},\"object\":{\"TempC_SHT\":23.5}}".getBytes(StandardCharsets.UTF_8);

        handler.handle("application/unknown/event/up", payload);

        verify(configCache, never()).resolveDevice(any(), any());
        verify(influxWriteService, never()).writeSensorPoints(anyInt(), any(), any(), any(), any(), any());
        // 식별 자체에 실패해 특정 device로 귀속시킬 수 없으므로 런타임 캐시에는 아무것도 쌓이지 않는다.
        assertThat(runtimeStatusCache.snapshotAll()).isEmpty();
    }

    @Test
    void devEuiPresentButUnregistered_ignoresWithoutTouchingCache() {
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"24E124710C123456\",\"deviceName\":\"dragino-lht65n-03\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);

        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("24E124710C123456")))
                .thenReturn(Optional.empty());

        handler.handle("application/dev4/event/up", payload);

        verify(configCache).resolveDevice(LoraIdType.DEV_EUI, "24E124710C123456");
        verify(configCache, never()).resolveDevice(eq(LoraIdType.DEVICE_NAME), any());
        verify(influxWriteService, never()).writeSensorPoints(anyInt(), any(), any(), any(), any(), any());
        // 미등록 장비는 runtime cache에 절대 기록되지 않는다.
        assertThat(runtimeStatusCache.snapshotAll()).isEmpty();
    }

    @Test
    void noMappingConfigured_recordsErrorWithoutInfluxWrite() {
        byte[] payload = "{\"deviceInfo\":{\"devEui\":\"AABBCCDDEEFF0011\"}}".getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(20, 21);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("AABBCCDDEEFF0011")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(21)).thenReturn(Set.of());

        handler.handle("application/dev5/event/up", payload);

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(20);
        assertThat(snapshot.lastErrorCode()).isEqualTo("NO_MAPPING");
        assertThat(snapshot.lastSavedAt()).isNull();
        verify(influxWriteService, never()).writeSensorPoints(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void sentinelValueIsSkippedSilentlyNotTreatedAsError() {
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"AABBCCDDEEFF0022\"},"
                + "\"object\":{\"TempC_SHT\":327.67}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(30, 31);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("AABBCCDDEEFF0022")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(31)).thenReturn(Set.of("object.TempC_SHT"));
        when(configCache.resolveMapping(31, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));

        handler.handle("application/dev6/event/up", payload);

        // sentinel(no-data)는 오류가 아니다. 다만 유효 포인트가 하나도 없으므로 Influx 기록도 없다.
        verify(influxWriteService, never()).writeSensorPoints(anyInt(), any(), any(), any(), any(), any());
        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(30);
        assertThat(snapshot.lastReceivedAt()).isNotNull();
        assertThat(snapshot.lastErrorCode()).isNull();
        assertThat(snapshot.lastSavedAt()).isNull();
    }

    @Test
    void influxWriteFails_recordsErrorAndRethrowsSoSubscriberErrorCountStillIncrements() {
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"AABBCCDDEEFF0044\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(40, 41);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("AABBCCDDEEFF0044")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(41)).thenReturn(Set.of("object.TempC_SHT"));
        when(configCache.resolveMapping(41, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));
        when(managerDeviceClient.findDevice(40)).thenReturn(Optional.empty());
        RuntimeException influxFailure = new RuntimeException("influx unavailable");
        doThrow(influxFailure).when(influxWriteService)
                .writeSensorPoints(eq(40), any(), any(), any(), isNull(), eq("mqtt"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.handle("application/dev7/event/up", payload));
        assertThat(thrown).isSameAs(influxFailure);

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(40);
        assertThat(snapshot.lastErrorCode()).isEqualTo("INFLUX_WRITE_FAILED");
        assertThat(snapshot.lastErrorMessage()).isEqualTo("influx unavailable");
        assertThat(snapshot.lastSavedAt()).isNull();
    }

    @Test
    void partialFieldConversionFailure_recordsFieldErrorButStillSavesConvertedFields() {
        // TempC_SHT는 정상 숫자, BatV는 숫자도 value_map도 아닌 값이라 변환 실패로 처리된다.
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"AABBCCDDEEFF0050\"},"
                + "\"object\":{\"TempC_SHT\":23.5,\"BatV\":\"n/a\"}}").getBytes(StandardCharsets.UTF_8);

        LoraResolvedDevice resolved = device(50, 51);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("AABBCCDDEEFF0050")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(51)).thenReturn(Set.of("object.TempC_SHT", "object.BatV"));
        when(configCache.resolveMapping(51, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));
        when(configCache.resolveMapping(51, "object.BatV"))
                .thenReturn(Optional.of(new LoraMappingRule("object.BatV", "BATTERY", "BATTERY", "V", null, null)));
        when(managerDeviceClient.findDevice(50)).thenReturn(Optional.empty());

        handler.handle("application/dev8/event/up", payload);

        ArgumentCaptor<Map<String, Object>> valuesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(influxWriteService).writeSensorPoints(eq(50), any(), valuesCaptor.capture(), any(), isNull(), eq("mqtt"));
        assertThat(valuesCaptor.getValue()).containsOnlyKeys("TEMPERATURE");

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(50);
        assertThat(snapshot.lastErrorCode()).isEqualTo("FIELD_CONVERSION_FAILED");
        assertThat(snapshot.lastSavedAt()).isNotNull();
        assertThat(snapshot.lastPointCount()).isEqualTo(1);
    }

    @Test
    void successfulSaveAfterPriorError_clearsErrorState() {
        LoraResolvedDevice resolved = device(60, 61);
        when(configCache.resolveDevice(eq(LoraIdType.DEV_EUI), eq("AABBCCDDEEFF0060")))
                .thenReturn(Optional.of(resolved));
        when(configCache.modelFieldsOf(61)).thenReturn(Set.of("object.TempC_SHT"));
        when(configCache.resolveMapping(61, "object.TempC_SHT"))
                .thenReturn(Optional.of(new LoraMappingRule("object.TempC_SHT", "TEMPERATURE", "TEMPERATURE", "C", null, null)));
        when(managerDeviceClient.findDevice(60)).thenReturn(Optional.empty());

        // 1차: Influx 저장 실패로 오류 상태가 기록된다.
        RuntimeException influxFailure = new RuntimeException("influx unavailable");
        doThrow(influxFailure).when(influxWriteService)
                .writeSensorPoints(eq(60), any(), any(), any(), isNull(), eq("mqtt"));
        byte[] payload = ("{\"deviceInfo\":{\"devEui\":\"AABBCCDDEEFF0060\"},"
                + "\"object\":{\"TempC_SHT\":23.5}}").getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> handler.handle("application/dev9/event/up", payload));
        assertThat(runtimeStatusCache.snapshotOf(60).lastErrorCode()).isEqualTo("INFLUX_WRITE_FAILED");

        // 2차: 같은 장비가 오류 없이 정상 저장에 성공하면 이전 오류 상태가 해제된다.
        org.mockito.Mockito.reset(influxWriteService);
        handler.handle("application/dev9/event/up", payload);

        LoraRuntimeStatusCache.Snapshot snapshot = runtimeStatusCache.snapshotOf(60);
        assertThat(snapshot.lastErrorCode()).isNull();
        assertThat(snapshot.lastErrorMessage()).isNull();
        assertThat(snapshot.lastErrorAt()).isNull();
        assertThat(snapshot.lastSavedAt()).isNotNull();
    }
}

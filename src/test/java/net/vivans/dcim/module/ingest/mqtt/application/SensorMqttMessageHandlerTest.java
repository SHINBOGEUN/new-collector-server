package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.influx.application.InfluxWriteService;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerDeviceClient;
import net.vivans.dcim.module.ingest.mqtt.config.SensorMqttProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SensorMqttMessageHandlerTest {

    private final ManagerDeviceClient manager = mock(ManagerDeviceClient.class);
    private final InfluxWriteService influx = mock(InfluxWriteService.class);
    private final SensorMqttMessageHandler handler = new SensorMqttMessageHandler(new ObjectMapper(), manager, influx,
            new SensorMqttProperties());

    @Test
    void routesCalculatedSnapshotWithoutDeviceLookup() {
        handler.handle("dcim/derived/calculated", bytes("""
                {"calculatedMetricId":3,"configVersion":2,"value":1.5,"inputs":{"FACILITY":150,"IT":100}}
                """));
        verify(influx).writeCalculated(eq(3), eq(2), eq(1.5),
                eq(Map.of("FACILITY", 150.0, "IT", 100.0)), any(Instant.class));
        verifyNoInteractions(manager);
    }

    @Test
    void preservesUtcTimestampFromCalculatedMessage() {
        handler.handle("dcim/derived/calculated", bytes("""
                {"datetime":"2026-10-01T15:00:00Z","calculatedMetricId":4,"configVersion":1,
                 "value":12.5,"inputs":{"POWER":12.5}}
                """));

        verify(influx).writeCalculated(eq(4), eq(1), eq(12.5), eq(Map.of("POWER", 12.5)),
                eq(Instant.parse("2026-10-01T15:00:00Z")));
    }

    @Test
    void forwardsModbusProtocolToExistingInfluxWriter() {
        when(manager.findDevice(101)).thenReturn(Optional.empty());

        handler.handle("dcim/sensor/data", bytes("""
                {"type":"schedule","protocol":"modbus","data":{"101":{"POWER":1250.5}}}
                """));

        verify(influx).writeSensorPoints(eq(101), isNull(), eq(Map.of("POWER", 1250.5)),
                any(Instant.class), isNull(), eq("modbus"));
    }

    @Test
    void legacyMessageStillUsesSnmp() {
        when(manager.findDevice(9)).thenReturn(Optional.empty());

        handler.handle("dcim/sensor/data", bytes("""
                {"type":"schedule","data":{"9":{"V":219}}}
                """));

        verify(influx).writeSensorPoints(eq(9), isNull(), eq(Map.of("V", 219)),
                any(Instant.class), isNull(), eq("snmp"));
    }

    @Test
    void unsupportedProtocolDoesNotWriteOrLookupDevice() {
        handler.handle("dcim/sensor/data", bytes("""
                {"type":"schedule","protocol":"unknown","data":{"9":{"V":219}}}
                """));

        verifyNoInteractions(manager, influx);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

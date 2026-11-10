package net.vivans.dcim.module.ingest.mqtt.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.lora.application.*;
import net.vivans.dcim.module.ingest.lora.infrastructure.ManagerLoraConfigClient;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraMqttSourceResponse;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.mqtt.application.*;
import net.vivans.dcim.module.ingest.mqtt.config.*;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ManagerSourceSubscriptionTest {
    private final LoraConfigCache cache = mock(LoraConfigCache.class);
    private final ManagerLoraConfigClient manager = mock(ManagerLoraConfigClient.class);
    private final LoraMqttMessageHandler lora = mock(LoraMqttMessageHandler.class);
    private final AgentMqttMessageHandler agent = mock(AgentMqttMessageHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LoraMqttSubscriber subscriber = new LoraMqttSubscriber(new LoraMqttProperties(), lora, agent, mapper, cache, manager);

    private LoraMqttSourceResponse source(int id, String type, String topic, boolean enabled, long version) {
        return new LoraMqttSourceResponse(id, "source-" + id, type, "tcp://broker:1883", topic, null, null, enabled, version);
    }

    @Test
    void managerSourcesAloneChooseBrokerTopicsAndHandlers() throws Exception {
        when(manager.findAllSources()).thenReturn(List.of(source(1, "CHIRPSTACK_LORA", "/application/#", true, 1),
                source(2, "AGENT", "net/vivans/gpu-mon/#", true, 1)));
        try (var clients = mockConstruction(MqttClient.class, (client, context) -> when(client.isConnected()).thenReturn(true))) {
            subscriber.refreshSources();
            assertThat(clients.constructed()).hasSize(2);
            var loraClient = clients.constructed().get(0);
            var gpuClient = clients.constructed().get(1);
            // HashMap 반복 순서에 의존하지 않도록 구독한 필터로 실제 mock을 찾는다.
            if (!mockingDetails(loraClient).getInvocations().stream().anyMatch(inv ->
                    inv.getMethod().getName().equals("subscribe") && "/application/#".equals(inv.getArgument(0)))) {
                var swap = loraClient; loraClient = gpuClient; gpuClient = swap;
            }
            verify(loraClient).subscribe("/application/#");
            verify(gpuClient).subscribe("net/vivans/gpu-mon/#");
            var loraCallback = ArgumentCaptor.forClass(MqttCallback.class);
            var gpuCallback = ArgumentCaptor.forClass(MqttCallback.class);
            verify(loraClient).setCallback(loraCallback.capture());
            verify(gpuClient).setCallback(gpuCallback.capture());
            byte[] gpuPayload = "{\"time\":\"2026-10-15T00:00:00Z\",\"deviceInfo\":{\"identityType\":\"MAC\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] loraPayload = "{\"deviceInfo\":{\"devEui\":\"a8404124875d9d36\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            gpuCallback.getValue().messageArrived("net/vivans/gpu-mon/server1", new MqttMessage(gpuPayload));
            loraCallback.getValue().messageArrived("/application/1/event/up", new MqttMessage(loraPayload));
            verify(agent).handle(mapper.readTree(gpuPayload));
            verify(lora).handle("/application/1/event/up", loraPayload);
            subscriber.refreshSources();
            assertThat(clients.constructed()).hasSize(2);
        }
    }

    @Test
    void initialConnectionFailureRetriesWithoutEditingSourceVersion() throws Exception {
        when(manager.findAllSources()).thenReturn(List.of(source(2, "AGENT", "net/vivans/gpu-mon/#", true, 1)));
        try (var clients = mockConstruction(MqttClient.class, (client, context) -> {
            if (context.getCount() == 1) doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR)).when(client).connect(any(MqttConnectOptions.class));
            else when(client.isConnected()).thenReturn(true);
        })) {
            subscriber.refreshSources();
            subscriber.refreshSources();
            assertThat(clients.constructed()).hasSize(2);
            verify(clients.constructed().get(0)).close(true);
            verify(clients.constructed().get(1)).subscribe("net/vivans/gpu-mon/#");
        }
    }

    @Test
    void managerFailureRetainsClientButDisabledAndDeletedSourcesDisconnect() throws Exception {
        when(manager.findAllSources()).thenReturn(List.of(source(2, "AGENT", "net/vivans/gpu-mon/#", true, 1)));
        try (var clients = mockConstruction(MqttClient.class, (client, context) -> when(client.isConnected()).thenReturn(true))) {
            subscriber.refreshSources();
            var connection = clients.constructed().get(0);
            when(manager.findAllSources()).thenThrow(new ManagerConfigUnavailableException("sources", new IllegalStateException("down")));
            subscriber.refreshSources();
            verify(connection, never()).close(true);
            doReturn(List.of(source(2, "AGENT", "net/vivans/gpu-mon/#", false, 2))).when(manager).findAllSources();
            subscriber.refreshSources();
            verify(connection).disconnect();
            verify(connection).close(true);
            when(manager.findAllSources()).thenReturn(List.of(source(2, "AGENT", "net/vivans/gpu-mon/#", true, 3)));
            subscriber.refreshSources();
            when(manager.findAllSources()).thenReturn(List.of());
            subscriber.refreshSources();
            verify(clients.constructed().get(1)).close(true);
        }
    }

    @Test
    void fixedSubscriberOnlySubscribesCollectorAndCalculated() throws Exception {
        var properties = new SensorMqttProperties();
        var fixed = new PahoMqttSubscriber(properties, mock(SensorMqttMessageHandler.class));
        try (var clients = mockConstruction(MqttClient.class)) {
            fixed.connectAndSubscribe();
            var client = clients.constructed().get(0);
            var callback = ArgumentCaptor.forClass(MqttCallback.class);
            verify(client).setCallback(callback.capture());
            ((MqttCallbackExtended) callback.getValue()).connectComplete(false, properties.getBrokerUrl());
            var topics = ArgumentCaptor.forClass(String[].class);
            verify(client).subscribe(topics.capture());
            assertThat(topics.getValue()).containsExactly("dcim/sensor/data", "dcim/derived/calculated");
        }
    }

    @Test
    void changedSourceVersionReplacesOldSubscription() throws Exception {
        when(manager.findAllSources()).thenReturn(List.of(source(2, "AGENT", "old/#", true, 1)),
                List.of(source(2, "AGENT", "new/#", true, 2)));
        try (var clients = mockConstruction(MqttClient.class, (client, context) -> when(client.isConnected()).thenReturn(true))) {
            subscriber.refreshSources();
            subscriber.refreshSources();
            assertThat(clients.constructed()).hasSize(2);
            verify(clients.constructed().get(0)).close(true);
            verify(clients.constructed().get(1)).subscribe("new/#");
        }
    }

    @Test
    void subscriptionFailureRetriesOnConnectedClient() throws Exception {
        when(manager.findAllSources()).thenReturn(List.of(source(2, "AGENT", "gpu/#", true, 1)));
        try (var clients = mockConstruction(MqttClient.class, (client, context) -> {
            when(client.isConnected()).thenReturn(true);
            doThrow(new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED)).doNothing().when(client).subscribe("gpu/#");
        })) {
            subscriber.refreshSources();
            subscriber.refreshSources();
            assertThat(clients.constructed()).hasSize(1);
            verify(clients.constructed().get(0), times(2)).subscribe("gpu/#");
        }
    }
}

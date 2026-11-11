package net.vivans.dcim.module.ingest.mqtt.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.lora.application.LoraConfigCache;
import net.vivans.dcim.module.ingest.lora.application.LoraMqttMessageHandler;
import net.vivans.dcim.module.ingest.lora.infrastructure.ManagerLoraConfigClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.mqtt.application.AgentMqttMessageHandler;
import net.vivans.dcim.module.ingest.mqtt.config.LoraMqttProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 설정을 못 받은 tick에서 구독을 해제하지 않는지 검증한다. */
class LoraMqttSubscriberGateTest {

    private LoraConfigCache cache;
    private ManagerLoraConfigClient client;
    private LoraMqttSubscriber subscriber;

    @BeforeEach
    void setUp() {
        cache = mock(LoraConfigCache.class);
        client = mock(ManagerLoraConfigClient.class);
        subscriber = new LoraMqttSubscriber(
                new LoraMqttProperties(), mock(LoraMqttMessageHandler.class),
                mock(AgentMqttMessageHandler.class), new ObjectMapper(), cache, client);
    }

    @Test
    void identityCacheUnavailableDoesNotBlockSourceListLookup() {
        when(cache.isLoaded()).thenReturn(false);
        when(client.findAllSources()).thenReturn(List.of());

        subscriber.refreshSources();

        verify(client).findAllSources();
        verify(client, never()).reportSourceStatus(anyInt(), any());
    }

    @Test
    void sourceListFailureLeavesExistingSubscriptionsAlone() {
        when(cache.isLoaded()).thenReturn(true);
        when(cache.hasAnyIdentity()).thenReturn(true);
        when(client.findAllSources())
                .thenThrow(new ManagerConfigUnavailableException("sources", new IllegalStateException("500")));

        subscriber.refreshSources();

        verify(client, times(1)).findAllSources();
        verify(client, never()).reportSourceStatus(anyInt(), any());
    }

    @Test
    void sourceListIsReadEvenBeforeAnyDeviceIsRegistered() {
        when(cache.isLoaded()).thenReturn(true);
        when(cache.hasAnyIdentity()).thenReturn(false);
        when(client.findAllSources()).thenReturn(List.of());

        subscriber.refreshSources();

        verify(client).findAllSources();
    }

    @Test
    void loadedConfigWithIdentityReconcilesSources() {
        when(cache.isLoaded()).thenReturn(true);
        when(cache.hasAnyIdentity()).thenReturn(true);
        when(client.findAllSources()).thenReturn(List.of());

        subscriber.refreshSources();

        verify(client, times(1)).findAllSources();
    }
}

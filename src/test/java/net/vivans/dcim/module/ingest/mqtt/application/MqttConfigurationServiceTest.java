package net.vivans.dcim.module.ingest.mqtt.application;

import java.util.List;
import java.util.Optional;
import net.vivans.dcim.module.ingest.manager.infrastructure.*;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.*;
import net.vivans.dcim.module.ingest.mqtt.config.SensorMqttProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttConfigurationServiceTest {
    private final ManagerMqttClient client = mock(ManagerMqttClient.class);
    private final ManagerServiceProperties manager = new ManagerServiceProperties();
    private final MqttConfigurationService config = new MqttConfigurationService(client, manager, new SensorMqttProperties());

    private MqttIdentityResponse identity(String mac) {
        return new MqttIdentityResponse(12, "MAC", mac, null, 40, "GPU1", 8, "GPU", true);
    }

    @Test
    void macUpdateReplacesOldMappingButKeepsDeviceId() {
        var point = MqttPointExtractorTest.point("load", "object.load", false, null, null);
        when(client.identities()).thenReturn(List.of(identity("aabbccddeeff")), List.of(identity("112233445566")));
        when(client.points()).thenReturn(List.of(point));
        when(client.resolve("MAC", "aabbccddeeff")).thenReturn(Optional.empty());
        config.refresh();
        assertThat(config.resolve("MAC", "aa:bb:cc:dd:ee:ff").orElseThrow().identity().deviceId()).isEqualTo(40);
        config.refresh();
        assertThat(config.resolve("MAC", "aabbccddeeff")).isEmpty();
        assertThat(config.resolve("MAC", "112233445566").orElseThrow().identity().deviceId()).isEqualTo(40);
    }

    @Test
    void cacheMissUsesResolveAndManagerDisabledNeverQueries() {
        when(client.identities()).thenReturn(List.of());
        when(client.points()).thenReturn(List.of(MqttPointExtractorTest.point("load", "object.load", false, null, null)));
        when(client.resolve("MAC", "aabbccddeeff")).thenReturn(Optional.of(identity("aabbccddeeff")));
        assertThat(config.resolve("MAC", "aabbccddeeff")).isPresent();
        verify(client).resolve("MAC", "aabbccddeeff");
        manager.setEnabled(false);
        clearInvocations(client);
        assertThat(config.resolve("MAC", "aabbccddeeff")).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void unavailableManagerDoesNotUseExpiredOrPartialSnapshot() {
        when(client.identities()).thenReturn(List.of(identity("aabbccddeeff")));
        when(client.points()).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(config::refresh).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.resolve("MAC", "aabbccddeeff")).isInstanceOf(IllegalStateException.class);
    }
}

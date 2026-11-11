package net.vivans.dcim.module.ingest.mqtt.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manager의 net.vivans.dcim.module.ingest.mqtt.domain.model.MqttExternalIdNormalizer와
 * 반드시 동일한 결과를 내야 한다. 등록(Manager)과 수신 조회(Sensor Data)의 정규화 규칙이
 * 어긋나면 등록된 장비가 있어도 MQTT 수신 시 매칭에 실패한다.
 */
class MqttExternalIdNormalizerTest {

    @Test
    void stripsColonsFromDevEui() {
        assertThat(MqttExternalIdNormalizer.normalize("DEV_EUI", "24:E1:24:71:0C:12:34:56"))
                .isEqualTo("24E124710C123456");
    }

    @Test
    void stripsDashesUnderscoresAndSpacesFromDeviceName() {
        assertThat(MqttExternalIdNormalizer.normalize("DEVICE_NAME", "dragino_lht65n-01"))
                .isEqualTo("DRAGINOLHT65N01");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(MqttExternalIdNormalizer.normalize("DEVICE_NAME", "  abc 123  "))
                .isEqualTo("ABC123");
    }

    @Test
    void alreadyNormalizedValueIsUnchanged() {
        assertThat(MqttExternalIdNormalizer.normalize("DEV_EUI", "24E124710C123456"))
                .isEqualTo("24E124710C123456");
    }

    @Test
    void macUsesSameRuleAsLoraTypes() {
        assertThat(MqttExternalIdNormalizer.normalize("MAC", "9c:6b:00:9b:4b:d7"))
                .isEqualTo("9C6B009B4BD7");
    }

    @Test
    void macMustContainTwelveHexDigits() {
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize("MAC", "9c:6b:00:9b:4b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uuidIsNormalizedToCanonicalForm() {
        assertThat(MqttExternalIdNormalizer.normalize("UUID", "  9B006B9C-464B-0000-0000-000000000000  "))
                .isEqualTo("9b006b9c-464b-0000-0000-000000000000");
    }

    @Test
    void serialKeepsCaseAndSeparators() {
        assertThat(MqttExternalIdNormalizer.normalize("SERIAL", "  SN-2026_a  "))
                .isEqualTo("SN-2026_a");
    }

    @Test
    void blankOrMissingValueIsRejected() {
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize("DEV_EUI", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize("DEV_EUI", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize(null, "24E124710C123456"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedTypeIsRejected() {
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize("IMEI", "123456789012345"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separatorOnlyValueIsRejectedInsteadOfBecomingBlank() {
        assertThatThrownBy(() -> MqttExternalIdNormalizer.normalize("DEVICE_NAME", "---"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

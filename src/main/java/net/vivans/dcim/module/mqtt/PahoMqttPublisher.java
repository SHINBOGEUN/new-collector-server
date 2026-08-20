package net.vivans.dcim.module.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "collector.mqtt.enabled", havingValue = "true")
public class PahoMqttPublisher implements MqttPublisher {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    @Value("${collector.mqtt.enabled:true}")
    private boolean enabled;

    @Value("${collector.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${collector.mqtt.client-id:new-collector-server}")
    private String clientId;

    @Value("${collector.mqtt.topic:dcim/sensor/data}")
    private String topic;

    @Value("${collector.mqtt.username:}")
    private String username;

    @Value("${collector.mqtt.password:}")
    private String password;

    private MqttClient client;

    @PostConstruct
    public void connect() {
        if (!enabled) {
            log.info("MQTT publish가 비활성화되어 있습니다.");
            return;
        }
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            if (username != null && !username.isBlank()) {
                options.setUserName(username);
                options.setPassword(password == null ? new char[0] : password.toCharArray());
            }
            client.connect(options);
            log.info("MQTT 연결: {}", brokerUrl);
        } catch (MqttException ex) {
            log.warn("MQTT 연결 실패 (수집은 계속, publish는 재시도): {} - {}", brokerUrl, ex.getMessage());
        }
    }

    @Override
    public void publishSensorReading(int taskId, int groupId, int deviceId, Map<String, Object> values) {
        if (!enabled) {
            return;
        }
        try {
            ensureConnected();
            if (client == null || !client.isConnected()) {
                log.warn("MQTT 미연결, device:{} 값을 건너뜁니다.", deviceId);
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "sensor");
            payload.put("datetime", LocalDateTime.now().format(DATETIME));
            payload.put("taskId", taskId);
            payload.put("groupId", groupId);
            payload.put("data", Map.of("device:" + deviceId, values));
            byte[] body = objectMapper.writeValueAsBytes(payload);
            MqttMessage message = new MqttMessage(body);
            message.setQos(0);
            message.setRetained(false);
            client.publish(topic, message);
            log.debug("MQTT publish device:{} topic={}", deviceId, topic);
        } catch (Exception ex) {
            log.warn("MQTT publish 실패 device:{}: {}", deviceId, ex.getMessage());
        }
    }

    private void ensureConnected() {
        if (client == null) {
            connect();
            return;
        }
        if (!client.isConnected()) {
            try {
                client.reconnect();
            } catch (MqttException ex) {
                log.debug("MQTT reconnect 실패: {}", ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void close() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ex) {
            log.warn("MQTT 종료 실패: {}", ex.getMessage());
        }
    }
}

package net.vivans.dcim.module.ingest.mqtt.infrastructure;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.mqtt.application.SensorMqttMessageHandler;
import net.vivans.dcim.module.ingest.mqtt.config.SensorMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingest.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class PahoMqttSubscriber {

    private final SensorMqttProperties properties;
    private final SensorMqttMessageHandler messageHandler;
    private MqttClient client;

    /** 운영 상태 화면에서 MQTT 수신 연결 여부를 보여주기 위해 사용한다. */
    public boolean isConnected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    @PostConstruct
    public void connectAndSubscribe() {
        if (!properties.isEnabled()) {
            log.info("MQTT subscribe disabled");
            return;
        }
        try {
            client = new MqttClient(properties.getBrokerUrl(), properties.getClientId(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                options.setUserName(properties.getUsername());
                options.setPassword(
                        properties.getPassword() == null ? new char[0] : properties.getPassword().toCharArray()
                );
            }
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    try { subscribeTopics(); }
                    catch (MqttException exception) { log.warn("MQTT subscription failed: {}", exception.getMessage()); }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    messageHandler.handle(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(options);
        } catch (MqttException exception) {
            log.warn("[MQTT_SUBSCRIBE_ERROR] broker={} exception={} message={}",
                    properties.getBrokerUrl(), exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private void subscribeTopics() throws MqttException {
        // 외부 장비(LoRa·GPU)는 Manager 소스 목록에서만 구독한다.
        var topics = java.util.stream.Stream.of(properties.getTopic(), properties.getCalculatedTopic())
                .filter(topic -> topic != null && !topic.isBlank()).distinct().toArray(String[]::new);
        if (topics.length > 0) client.subscribe(topics);
        log.info("[MQTT_SUBSCRIBE_END] topics={} broker={}", java.util.Arrays.toString(topics), properties.getBrokerUrl());
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
        } catch (MqttException exception) {
            log.warn("MQTT close failed: {}", exception.getMessage());
        }
    }
}

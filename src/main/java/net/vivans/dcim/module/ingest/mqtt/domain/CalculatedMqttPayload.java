package net.vivans.dcim.module.ingest.mqtt.domain;

import java.util.Map;

public record CalculatedMqttPayload(String datetime, Integer calculatedMetricId, Integer configVersion,
                                    Double value, Map<String, Double> inputs) { }

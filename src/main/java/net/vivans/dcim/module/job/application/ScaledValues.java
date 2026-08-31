package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionPointSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SNMP raw 값에 point scale을 곱한다. scale null/1이면 원본을 유지한다.
 */
final class ScaledValues {

    private ScaledValues() {
    }

    static Map<String, Object> apply(
            Map<String, Object> values,
            Collection<CollectionGroupOidSpec> oids
    ) {
        if (values == null || values.isEmpty()) {
            return values == null ? Map.of() : values;
        }
        Map<String, Double> scales = new LinkedHashMap<>();
        if (oids != null) {
            for (CollectionGroupOidSpec oid : oids) {
                if (oid != null && oid.name() != null) {
                    scales.put(oid.name(), oid.scale());
                }
            }
        }
        return applyWithScales(values, scales);
    }

    static Map<String, Object> applyLive(
            Map<String, Object> values,
            Collection<LiveCollectionPointSpec> points
    ) {
        if (values == null || values.isEmpty()) {
            return values == null ? Map.of() : values;
        }
        Map<String, Double> scales = new LinkedHashMap<>();
        if (points != null) {
            for (LiveCollectionPointSpec point : points) {
                if (point != null && point.name() != null) {
                    scales.put(point.name(), point.scale());
                }
            }
        }
        return applyWithScales(values, scales);
    }

    static Object applyOne(Object value, Double scale) {
        if (value == null) {
            return null;
        }
        if (scale == null || Double.compare(scale, 1.0d) == 0) {
            return value;
        }
        Double numeric = toDoubleOrNull(value);
        if (numeric == null) {
            return value;
        }
        return numeric * scale;
    }

    private static Map<String, Object> applyWithScales(Map<String, Object> values, Map<String, Double> scales) {
        Map<String, Object> scaled = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            scaled.put(entry.getKey(), applyOne(entry.getValue(), scales.get(entry.getKey())));
        }
        return scaled;
    }

    private static Double toDoubleOrNull(Object value) {
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            return Double.isFinite(converted) ? converted : null;
        }
        try {
            double converted = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(converted) ? converted : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

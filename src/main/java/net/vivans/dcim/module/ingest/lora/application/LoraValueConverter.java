package net.vivans.dcim.module.ingest.lora.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.lora.domain.LoraSentinelValues;
import org.springframework.stereotype.Component;

/**
 * payload에서 뽑아낸 원시 JsonNode를 InfluxDB에 쓸 숫자로 변환한다.
 * 순서: value_map(상태값→숫자, 있으면 우선) → 숫자 파싱 → Dragino sentinel(무의미값) 제외 → scale 적용.
 * 무의미값(sentinel)은 "정상적으로 값이 없는 상태"라 오류로 취급하지 않고 조용히 건너뛴다 (레거시와 동일 동작).
 * 그 외 숫자로도, value_map으로도 해석 안 되는 값만 변환 실패로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class LoraValueConverter {

    private final ObjectMapper objectMapper;

    public LoraConversionResult convert(JsonNode rawValue, Double scale, String valueMap) {
        if (rawValue == null) {
            return LoraConversionResult.noData();
        }

        Double numeric = tryValueMap(rawValue, valueMap);
        if (numeric == null) {
            numeric = tryNumeric(rawValue);
        }
        if (numeric == null) {
            return LoraConversionResult.failed();
        }
        if (LoraSentinelValues.isNoData(numeric)) {
            return LoraConversionResult.noData();
        }
        double scaled = scale == null ? numeric : numeric * scale;
        if (!Double.isFinite(scaled)) {
            return LoraConversionResult.failed();
        }
        return LoraConversionResult.ok(scaled);
    }

    private Double tryValueMap(JsonNode rawValue, String valueMap) {
        if (valueMap == null || valueMap.isBlank()) {
            return null;
        }
        try {
            JsonNode map = objectMapper.readTree(valueMap);
            String key = rawValue.isTextual() ? rawValue.asText() : rawValue.asText(null);
            if (key == null) {
                return null;
            }
            JsonNode mapped = map.get(key);
            if (mapped == null) {
                // 대소문자 차이 허용 (예: "Leak" vs "leak")
                for (var it = map.fields(); it.hasNext(); ) {
                    var entry = it.next();
                    if (entry.getKey().equalsIgnoreCase(key)) {
                        mapped = entry.getValue();
                        break;
                    }
                }
            }
            return mapped != null && mapped.isNumber() ? mapped.doubleValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Double tryNumeric(JsonNode rawValue) {
        if (rawValue.isNumber()) {
            double v = rawValue.doubleValue();
            return Double.isFinite(v) ? v : null;
        }
        if (rawValue.isTextual()) {
            try {
                double v = Double.parseDouble(rawValue.asText().trim());
                return Double.isFinite(v) ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (rawValue.isBoolean()) {
            return rawValue.asBoolean() ? 1.0 : 0.0;
        }
        return null;
    }
}

package net.vivans.dcim.module.ingest.lora.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 매핑 테이블의 payload_field(간단한 JSON 경로 문자열)로 실제 payload에서 값을 찾는다.
 * 지원 문법: 점(.) 표기 + 배열 인덱스 1단계. 예: "object.TempC_SHT", "rxInfo[0].rssi".
 * 레거시 Dragino 처리 코드(JsonPath 라이브러리)가 쓰던 경로 스타일을 그대로 표현할 수 있게 만들었다.
 */
public final class LoraPayloadPathResolver {

    private static final Pattern ARRAY_SEGMENT = Pattern.compile("^([^\\[]+)\\[(\\d+)]$");

    private LoraPayloadPathResolver() {
    }

    public static JsonNode resolve(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            Matcher matcher = ARRAY_SEGMENT.matcher(segment);
            if (matcher.matches()) {
                current = current.path(matcher.group(1));
                current = current.path(Integer.parseInt(matcher.group(2)));
            } else {
                current = current.path(segment);
            }
        }
        return (current == null || current.isMissingNode() || current.isNull()) ? null : current;
    }
}

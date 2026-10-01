package net.vivans.dcim.module.modbus;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 일부 point가 실패해도 이미 읽은 값은 호출자가 저장할 수 있도록 전달한다. */
public final class ModbusPartialReadException extends IOException {
    private final Map<String, Object> values;
    private final Map<String, String> failures;

    public ModbusPartialReadException(Map<String, Object> values, Map<String, String> failures) {
        super("Modbus point failures (" + failures.size() + "): " + failures.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "))
                + (failures.size() > 3 ? "; ..." : ""));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public Map<String, Object> values() {
        return values;
    }

    public Map<String, String> failures() {
        return failures;
    }
}

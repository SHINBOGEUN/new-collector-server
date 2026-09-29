package net.vivans.dcim.module.job.domain.modbus;

import net.vivans.dcim.module.job.domain.CollectionGroupSpec;

import java.util.List;

/** Modbus 그룹 공통 point와 target별 point를 담는 spec. */
public record ModbusCollectionGroupSpec(
        Integer taskId,
        Integer groupId,
        Integer modelId,
        String protocol,
        String cronExpression,
        int timeoutMs,
        int retries,
        int maxConcurrency,
        List<CollectionGroupModbusPointSpec> points,
        List<ModbusCollectionTargetSpec> targets,
        List<String> skipped
) implements CollectionGroupSpec {
    public ModbusCollectionGroupSpec {
        if (!"modbus".equals(protocol)) {
            throw new IllegalArgumentException("protocol must be modbus");
        }
    }
}

package net.vivans.dcim.module.job.domain.modbus;

import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;

import java.util.List;

public record ModbusCollectionTargetSpec(
        Integer deviceId,
        String host,
        int port,
        Integer unitId,
        List<CollectionGroupModbusPointSpec> points
) implements CollectionGroupTargetSpec {
}

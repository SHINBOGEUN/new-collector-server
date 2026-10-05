package net.vivans.dcim.module.job.domain;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

import java.util.Map;

public record LiveModbusTargetSpec(
        String deviceName,
        Integer sourceDeviceId,
        ModbusCollectionTargetSpec target,
        Map<String, String> units
) {
}

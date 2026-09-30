package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ModbusQueryClient {
    Map<String, Object> read(ModbusCollectionTargetSpec target, List<CollectionGroupModbusPointSpec> points,
                             int timeoutMs, int retries) throws IOException;
}

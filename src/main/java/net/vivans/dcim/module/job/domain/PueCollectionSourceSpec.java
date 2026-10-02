package net.vivans.dcim.module.job.domain;
import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
public record PueCollectionSourceSpec(Integer deviceId, String host, int port, String pointName, String oid, Double scale,
                                      String alias, String protocol, Integer unitId,
                                      CollectionGroupModbusPointSpec modbusPoint) {
}

package net.vivans.dcim.module.job.domain.modbus;

import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;

import java.util.List;

public record ModbusCollectionTargetSpec(
        Integer deviceId,
        String host,
        int port,
        Integer unitId,
        List<CollectionGroupModbusPointSpec> points,
        Integer sourceDeviceId
) implements CollectionGroupTargetSpec {
    public ModbusCollectionTargetSpec(Integer deviceId,String host,int port,Integer unitId,List<CollectionGroupModbusPointSpec> points){this(deviceId,host,port,unitId,points,null);}
}

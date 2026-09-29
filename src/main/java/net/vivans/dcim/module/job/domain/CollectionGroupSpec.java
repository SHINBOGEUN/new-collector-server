package net.vivans.dcim.module.job.domain;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;

import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** 작업 관리에 필요한 공통 정보. JSON의 protocol로 전용 spec을 선택한다. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "protocol", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = SnmpCollectionGroupSpec.class, name = "snmp"),
        @JsonSubTypes.Type(value = ModbusCollectionGroupSpec.class, name = "modbus")
})
public interface CollectionGroupSpec {
    Integer taskId();
    Integer groupId();
    Integer modelId();
    String protocol();
    String cronExpression();
    int timeoutMs();
    int retries();
    int maxConcurrency();
    List<? extends CollectionGroupTargetSpec> targets();
    List<String> skipped();
}

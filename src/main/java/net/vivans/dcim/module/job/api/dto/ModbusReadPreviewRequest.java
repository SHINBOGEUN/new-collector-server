package net.vivans.dcim.module.job.api.dto;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

import java.util.List;

/** Manager가 DB 설정으로 만든 일회성 읽기 요청. 정기 job/MQTT에는 반영하지 않는다. */
public record ModbusReadPreviewRequest(
        List<ModbusCollectionTargetSpec> targets,
        int timeoutMs,
        int retries
) {
}

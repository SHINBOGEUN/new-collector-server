package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 정기 수집 작업의 공통 설정을 검증한 뒤
 * 프로토콜별 검증기로 위임한다.
 */
@Component
@RequiredArgsConstructor
public class CollectionSpecValidator {

    private final SnmpSpecValidator snmpSpecValidator;
    private final ModbusSpecValidator modbusSpecValidator;

    public void validate(CollectionGroupSpec spec) {
        validateCommon(spec);

        if (spec instanceof SnmpCollectionGroupSpec snmpSpec) {
            snmpSpecValidator.validate(snmpSpec);
        } else if (spec instanceof ModbusCollectionGroupSpec modbusSpec) {
            modbusSpecValidator.validate(modbusSpec);
        } else {
            throw new IllegalArgumentException("지원하지 않는 수집 spec입니다: " + spec.protocol());
        }
    }

    private void validateCommon(CollectionGroupSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec이 필요합니다.");
        }

        if (spec.groupId() == null || spec.taskId() == null) {
            throw new IllegalArgumentException("taskId와 groupId가 필요합니다.");
        }

        if (spec.cronExpression() == null
                || spec.cronExpression().isBlank()) {
            throw new IllegalArgumentException("cronExpression이 필요합니다.");
        }

        try {
            new CronTrigger(
                    spec.cronExpression(),
                    ZoneId.systemDefault());

        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cronExpression이 올바르지 않습니다: "
                            + spec.cronExpression(), exception);
        }
    }
}
package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

class CollectionSpecValidatorTest {
    private final SnmpSpecValidator snmp = mock(SnmpSpecValidator.class);
    private final ModbusSpecValidator modbus = mock(ModbusSpecValidator.class);
    private final CollectionSpecValidator validator = new CollectionSpecValidator(snmp, modbus);

    @Test
    void dispatchesSnmpOnly() {
        var spec = spec(1, 2, "0 * * * * *");
        validator.validate(spec);
        verify(snmp).validate(spec);
        verifyNoInteractions(modbus);
    }

    @Test
    void dispatchesModbusOnly() {
        var spec = new ModbusCollectionGroupSpec(1, 2, 3, "modbus",
                "0 * * * * *", 2000, 1, 10, List.of(), List.of(), List.of());
        validator.validate(spec);
        verify(modbus).validate(spec);
        verifyNoInteractions(snmp);
    }

    @Test
    void rejectsInvalidCommonFieldsBeforeDispatch() {
        CollectionGroupSpec[] invalid = {null, spec(null, 2, "0 * * * * *"),
                spec(1, null, "0 * * * * *"), spec(1, 2, null),
                spec(1, 2, " "), spec(1, 2, "not-a-cron")};
        for (var spec : invalid) {
            assertThatThrownBy(() -> validator.validate(spec))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(snmp, modbus);
    }

    @Test
    void propagatesProtocolFailure() {
        var spec = spec(1, 2, "0 * * * * *");
        var failure = new IllegalArgumentException("invalid OID");
        doThrow(failure).when(snmp).validate(spec);
        assertThatThrownBy(() -> validator.validate(spec)).isSameAs(failure);
        verifyNoInteractions(modbus);
    }

    private SnmpCollectionGroupSpec spec(Integer task, Integer group, String cron) {
        return new SnmpCollectionGroupSpec(task, group, 3, "snmp", cron,
                null, 2000, 1, 10, List.of(), List.of(), List.of());
    }
}

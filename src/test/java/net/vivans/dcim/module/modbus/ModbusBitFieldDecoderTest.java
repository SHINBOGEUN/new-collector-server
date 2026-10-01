package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.ModbusBitFieldSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModbusBitFieldDecoderTest {
    @Test
    void valveStatusFrom602FollowsConfiguredPairsAndFallback() {
        Map<String, Long> statuses = Map.of("1", 0L, "2", 1L);
        assertEquals(1L, ModbusBitFieldDecoder.decode(602, 16,
                new ModbusBitFieldSpec("VALVE_1", 0, 2, statuses, -1L)));
        assertEquals(1L, ModbusBitFieldDecoder.decode(602, 16,
                new ModbusBitFieldSpec("VALVE_2", 2, 2, statuses, -1L)));
        assertEquals(0L, ModbusBitFieldDecoder.decode(602, 16,
                new ModbusBitFieldSpec("VALVE_4", 6, 2, statuses, -1L)));
        assertEquals(1L, ModbusBitFieldDecoder.decode(602, 16,
                new ModbusBitFieldSpec("VALVE_5", 8, 2, statuses, -1L)));
        assertEquals(-1L, ModbusBitFieldDecoder.decode(0, 16,
                new ModbusBitFieldSpec("UNKNOWN", 0, 2, statuses, -1L)));
    }

    @Test
    void supportsOtherWidthsAndUnsignedThirtyTwoBitValues() {
        assertEquals(15L, ModbusBitFieldDecoder.decode(-1, 32,
                new ModbusBitFieldSpec("NIBBLE", 28, 4, Map.of(), null)));
        assertEquals(3L, ModbusBitFieldDecoder.decode(0b1100, 16,
                new ModbusBitFieldSpec("FIELD", 2, 2, Map.of(), null)));
    }
}

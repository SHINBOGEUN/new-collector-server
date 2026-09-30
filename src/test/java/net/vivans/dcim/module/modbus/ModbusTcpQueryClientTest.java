package net.vivans.dcim.module.modbus;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusTcpQueryClientTest {

    @Test
    void acceptsStandardAndSingleBytePrefixedResponses() throws Exception {
        assertThat(ModbusTcpQueryClient.readTransactionId(input(0, 1), 1)).isEqualTo(1);
        assertThat(ModbusTcpQueryClient.readTransactionId(input(0x21, 0, 1), 1)).isEqualTo(1);
    }

    @Test
    void rejectsUnmatchedTransactionId() {
        assertThatThrownBy(() -> ModbusTcpQueryClient.readTransactionId(input(0x21, 0, 2), 1))
                .isInstanceOf(IOException.class);
    }

    private static DataInputStream input(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}

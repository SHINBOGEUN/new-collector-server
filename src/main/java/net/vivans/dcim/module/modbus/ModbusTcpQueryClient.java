package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusBitFieldSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modbus TCP FC01/02/03/04. 주소는 spec의 0 기반 값을 그대로 사용한다. */
@Component
public class ModbusTcpQueryClient implements ModbusQueryClient {

    @Override
    public Map<String, Object> read(ModbusCollectionTargetSpec target, List<CollectionGroupModbusPointSpec> points,
                                    int timeoutMs, int retries) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Socket socket = null;
        int transactionId = 0;
        try {
            for (CollectionGroupModbusPointSpec point : points) {
                Exception lastFailure = null;
                for (int attempt = 0; attempt <= Math.max(retries, 0); attempt++) {
                    try {
                        if (socket == null) {
                            socket = connect(target, timeoutMs);
                        }
                        Number value = readPoint(new DataInputStream(socket.getInputStream()),
                                new DataOutputStream(socket.getOutputStream()), target.unitId(),
                                ++transactionId, point);
                        Map<String, Object> pointValues = new LinkedHashMap<>();
                        pointValues.put(point.name(), value);
                        if (point.bitFields() != null) {
                            for (ModbusBitFieldSpec field : point.bitFields()) {
                                pointValues.put(field.name(), ModbusBitFieldDecoder.decode(value,
                                        point.dataType().getRegisterCount() * 16, field));
                            }
                        }
                        values.putAll(pointValues);
                        lastFailure = null;
                        break;
                    } catch (IOException | RuntimeException exception) {
                        lastFailure = exception;
                        close(socket);
                        socket = null;
                    }
                }
                if (lastFailure != null) {
                    failures.put(point.name(), lastFailure.getMessage() == null
                            ? lastFailure.getClass().getSimpleName() : lastFailure.getMessage());
                }
            }
        } finally {
            close(socket);
        }
        if (!failures.isEmpty()) {
            throw new ModbusPartialReadException(values, failures);
        }
        return values;
    }

    private static Socket connect(ModbusCollectionTargetSpec target, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(target.host(), target.port()), Math.max(timeoutMs, 1));
            socket.setSoTimeout(Math.max(timeoutMs, 1));
            return socket;
        } catch (IOException exception) {
            close(socket);
            throw exception;
        }
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 실패한 연결을 버리고 다음 point는 새 연결로 시도한다.
        }
    }

    private static Number readPoint(DataInputStream input, DataOutputStream output, int unitId,
                                    int transactionId, CollectionGroupModbusPointSpec point) throws IOException {
        int function = functionCode(point.registerType());
        int quantity = point.registerType().isBitType() ? 1 : point.dataType().getRegisterCount();
        // 일부 TCP/RTU 게이트웨이는 여러 번의 작은 write로 나뉜 MBAP 요청을
        // 완전한 프레임으로 기다리지 않고 연결을 닫는다. 한 버퍼에 조립해 한 번에 보낸다.
        ByteArrayOutputStream frame = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(frame);
        request.writeShort(transactionId);
        request.writeShort(0); // protocol ID
        request.writeShort(6); // unit ID + function + address + quantity
        request.writeByte(unitId);
        request.writeByte(function);
        request.writeShort(point.address());
        request.writeShort(quantity);
        output.write(frame.toByteArray());
        output.flush();

        int responseTransaction = readTransactionId(input, transactionId);
        int protocolId = input.readUnsignedShort();
        int length = input.readUnsignedShort();
        int responseUnit = input.readUnsignedByte();
        if (responseTransaction != transactionId || protocolId != 0 || responseUnit != unitId
                || length < 3 || length > 254) {
            throw new IOException("invalid Modbus TCP response header for " + point.name());
        }
        int responseFunction = input.readUnsignedByte();
        if (responseFunction == (function | 0x80)) {
            if (length != 3) {
                throw new IOException("invalid Modbus exception length for " + point.name());
            }
            throw new IOException("Modbus exception " + input.readUnsignedByte() + " for " + point.name());
        }
        if (responseFunction != function) {
            throw new IOException("unexpected Modbus function " + responseFunction + " for " + point.name());
        }
        int byteCount = input.readUnsignedByte();
        int expectedBytes = point.registerType().isBitType() ? 1 : quantity * 2;
        if (length != byteCount + 3 || byteCount != expectedBytes) {
            throw new IOException("invalid Modbus byte count for " + point.name());
        }
        byte[] bytes = new byte[byteCount];
        input.readFully(bytes);
        return ModbusValueDecoder.decode(point, bytes);
    }

    static int readTransactionId(DataInputStream input, int expected) throws IOException {
        int first = input.readUnsignedByte();
        int second = input.readUnsignedByte();
        int actual = (first << 8) | second;
        if (actual == expected) return actual;
        // 일부 게이트웨이는 첫 응답의 정상 MBAP 프레임 앞에 1바이트를 붙인다.
        // 예상 transaction ID가 정확히 한 바이트 뒤에 있을 때만 그 바이트를 무시한다.
        int third = input.readUnsignedByte();
        actual = (second << 8) | third;
        if (actual != expected) throw new IOException("invalid Modbus transaction ID");
        return actual;
    }

    private static int functionCode(ModbusRegisterType type) {
        return switch (type) {
            case COIL -> 1;
            case DISCRETE -> 2;
            case HOLDING -> 3;
            case INPUT -> 4;
        };
    }
}

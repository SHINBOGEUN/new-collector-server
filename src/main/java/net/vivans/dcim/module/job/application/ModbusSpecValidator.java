package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.modbus.*;
import org.springframework.stereotype.Component;

import java.util.*;


/**
 * Modbus 정기 수집 spec의 target과 point 설정을 검증한다.
 * 공통 작업 설정과 cron 검증은 공통 검증기에서 담당한다.
 */
@Component
public class ModbusSpecValidator {

    private static final int MAX_PORT = 65535;
    private static final int MAX_UNIT_ID = 247;
    private static final int MAX_ADDRESS = 65535;

    public void validate(ModbusCollectionGroupSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Modbus spec is required");
        }

        if (spec.targets() == null || spec.targets().isEmpty()) {
            return;
        }

        // 같은 저장 대상 장비에 같은 이름의 값이 중복되는지 검사한다.
        Map<Integer, Set<String>> namesByDevice = new HashMap<>();

        for (int targetIndex = 0;
             targetIndex < spec.targets().size();
             targetIndex++) {

            ModbusCollectionTargetSpec target = spec.targets().get(targetIndex);
            String targetPath = "targets[" + targetIndex + "]";

            validateTarget(target, targetPath);

            // target 전용 points가 있으면 사용하고,
            // 없으면 그룹 공통 points를 사용한다.
            List<CollectionGroupModbusPointSpec> points = ModbusPointResolver.resolve(spec, target);

            Set<String> names = namesByDevice.computeIfAbsent(target.deviceId(), ignored -> new HashSet<>());

            for (int pointIndex = 0;
                 pointIndex < points.size();
                 pointIndex++) {

                CollectionGroupModbusPointSpec point =
                        points.get(pointIndex);
                String pointPath =
                        targetPath + ".effectivePoints[" + pointIndex + "]";

                validatePoint(point, pointPath);

                if (!names.add(point.name())) {
                    throw invalid(pointPath,
                            "duplicate point name for deviceId="
                                    + target.deviceId()
                                    + ": " + point.name()
                    );
                }
                if (point.bitFields() != null) {
                    for (ModbusBitFieldSpec field : point.bitFields()) {
                        validateBitField(point, field, pointPath);
                        if (!names.add(field.name())) {
                            throw invalid(pointPath, "duplicate derived point name for deviceId="
                                    + target.deviceId() + ": " + field.name());
                        }
                    }
                }
            }
        }
    }

    private void validateTarget(
            ModbusCollectionTargetSpec target,
            String path
    ) {
        if (target == null) {
            throw invalid(path, "target is required");
        }

        if (target.deviceId() == null || target.deviceId() <= 0) {
            throw invalid(path, "deviceId must be positive");
        }

        if (target.host() == null || target.host().isBlank()) {
            throw invalid(path, "host is required");
        }

        if (target.port() < 1 || target.port() > MAX_PORT) {
            throw invalid(path, "port must be between 1 and 65535");
        }

        if (target.unitId() == null
                || target.unitId() < 0
                || target.unitId() > MAX_UNIT_ID) {
            throw invalid(path, "unitId must be between 0 and 247");
        }
    }

    private void validatePoint(
            CollectionGroupModbusPointSpec point,
            String path
    ) {
        if (point == null) {
            throw invalid(path, "point is required");
        }

        validateName(point.name(), path);

        if (point.registerType() == null) {
            throw invalid(path, "registerType is required");
        }

        if (point.dataType() == null) {
            throw invalid(path, "dataType is required");
        }

        if (point.address() == null
                || point.address() < 0
                || point.address() > MAX_ADDRESS) {
            throw invalid(path, "address must be between 0 and 65535");
        }

        if (!Double.isFinite(point.effectiveScale())) {
            throw invalid(path, "scale must be finite");
        }
        if (!Double.isFinite(point.effectiveOffset())) {
            throw invalid(path, "offset must be finite");
        }

        // 비트 읽기는 한 주소에서 비트 하나를 읽는다.
        // dataType과 byteOrder를 이용한 레지스터 해석은 하지 않는다.
        if (point.registerType().isBitType()) {
            if (point.effectiveOffset() != 0.0) {
                throw invalid(path, "offset is not supported for bit reads");
            }
            return;
        }

        int registerCount = point.dataType().getRegisterCount();
        long lastAddress = (long) point.address() + registerCount - 1;

        if (lastAddress > MAX_ADDRESS) {
            throw invalid(path, "register range exceeds address 65535");
        }

        validateByteOrder(point, path);
    }

    private void validateBitField(CollectionGroupModbusPointSpec point, ModbusBitFieldSpec field, String path) {
        if (field == null) throw invalid(path, "bit field is required");
        validateName(field.name(), path);
        if (point.registerType().isBitType() || point.dataType() == ModbusDataType.FLOAT32
                || point.effectiveScale() != 1.0 || point.effectiveOffset() != 0.0) {
            throw invalid(path, "bit fields require an unscaled integer register point");
        }
        int bits = point.dataType().getRegisterCount() * 16;
        if (field.bitOffset() < 0 || field.bitWidth() < 1 || field.bitWidth() > 32
                || (long) field.bitOffset() + field.bitWidth() > bits) {
            throw invalid(path, "bit range exceeds source register width");
        }
        if (field.valueMap() != null) {
            long max = (1L << field.bitWidth()) - 1;
            for (String key : field.valueMap().keySet()) {
                try {
                    long code = Long.parseLong(key);
                    if (code < 0 || code > max || !Long.toString(code).equals(key)
                            || field.valueMap().get(key) == null) {
                        throw invalid(path, "invalid bit value mapping: " + key);
                    }
                } catch (NumberFormatException exception) {
                    throw invalid(path, "invalid bit value mapping: " + key);
                }
            }
        }
    }

    private void validateName(String name, String path) {
        if (name == null || name.isBlank()) {
            throw invalid(path, "name is required");
        }

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);

            if (Character.isWhitespace(character)
                    || Character.isSpaceChar(character)
                    || character == ','
                    || character == '='
                    || character == '"') {
                throw invalid(
                        path,
                        "name must not contain whitespace, comma, "
                                + "equals sign or double quote"
                );
            }
        }
    }

    private void validateByteOrder(
            CollectionGroupModbusPointSpec point,
            String path
    ) {
        ModbusByteOrder byteOrder = point.effectiveByteOrder();

        if (point.dataType().isMultiRegister()) {
            if (byteOrder == null || byteOrder.isSingleRegisterOrder()) {
                throw invalid(
                        path,
                        "32-bit data requires ABCD, CDAB, BADC or DCBA"
                );
            }
            return;
        }

        if (byteOrder == null || !byteOrder.isSingleRegisterOrder()) {
            throw invalid(path, "16-bit data requires null, AB or BA");
        }
    }

    private IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }

}

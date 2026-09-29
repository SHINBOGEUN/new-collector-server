package net.vivans.dcim.module.job.domain.modbus;

import java.util.List;
import java.util.Objects;

/**
 * target이 사용할 Modbus point 목록을 선택한다.
 *
 * target 전용 목록이 있으면 그것만 사용하고,
 * 없으면 그룹 공통 목록을 사용한다.
 */
public final class ModbusPointResolver {

    private ModbusPointResolver() {
    }

    public static List<CollectionGroupModbusPointSpec> resolve(
            ModbusCollectionGroupSpec group,
            ModbusCollectionTargetSpec target
    ) {
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(target, "target must not be null");

        if (target.points() != null) {
            return target.points();
        }

        if (group.points() != null) {
            return group.points();
        }

        return List.of();
    }
}

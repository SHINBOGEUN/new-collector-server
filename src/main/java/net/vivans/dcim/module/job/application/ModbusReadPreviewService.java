package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.job.api.dto.ModbusReadPreviewRequest;
import net.vivans.dcim.module.job.api.dto.ModbusReadPreviewResponse;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ModbusReadPreviewService {
    private final ModbusSpecValidator validator;
    private final ModbusQueryClient queryClient;

    public ModbusReadPreviewResponse read(ModbusReadPreviewRequest request) {
        if (request == null || request.targets() == null || request.targets().isEmpty()
                || request.targets().size() > 50 || request.timeoutMs() < 1 || request.timeoutMs() > 10000
                || request.retries() < 0 || request.retries() > 2
                || request.targets().stream().anyMatch(target -> target == null || target.points() == null
                    || target.points().isEmpty())
                || request.targets().stream().mapToInt(target -> target.points().size()).sum() > 100) {
            throw new IllegalArgumentException("invalid Modbus preview request");
        }
        ModbusCollectionGroupSpec spec = new ModbusCollectionGroupSpec(
                0, 0, 0, "modbus", "", request.timeoutMs(), request.retries(), 1,
                List.of(), request.targets(), List.of());
        validator.validate(spec);

        List<ModbusReadPreviewResponse.TargetResult> results = new ArrayList<>();
        for (ModbusCollectionTargetSpec target : request.targets()) {
            long start = System.nanoTime();
            try {
                Map<String, Object> values = queryClient.read(target, target.points(),
                        request.timeoutMs(), request.retries());
                results.add(new ModbusReadPreviewResponse.TargetResult(target.deviceId(), target.host(),
                        target.port(), target.unitId(), values, null, (System.nanoTime() - start) / 1_000_000));
            } catch (Exception exception) {
                String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                results.add(new ModbusReadPreviewResponse.TargetResult(target.deviceId(), target.host(),
                        target.port(), target.unitId(), Map.of(), reason,
                        (System.nanoTime() - start) / 1_000_000));
            }
        }
        return new ModbusReadPreviewResponse(results);
    }
}

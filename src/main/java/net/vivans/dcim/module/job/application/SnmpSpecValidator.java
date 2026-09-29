package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.job.domain.snmp.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionTargetSpec;
import org.snmp4j.smi.OID;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * SNMP 정기 수집의 대상과 OID 설정을 검증한다.
 * 공통 작업 설정과 cron 검증은 공통 검증기에서 담당한다.
 */
@Component
@RequiredArgsConstructor
public class SnmpSpecValidator {

    private static final int MAX_PORT = 65535;
    private static final String INSTANCE_PLACEHOLDER = "{instanceId}";

    private final OidTemplateResolver oidTemplateResolver;

    public void validate(SnmpCollectionGroupSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("SNMP spec is required");
        }

        // 수집 대상이 없는 그룹은 유지할 수 있다.
        if (spec.targets() == null || spec.targets().isEmpty()) {
            return;
        }

        if (spec.oids() == null || spec.oids().isEmpty()) {
            throw invalid("oids", "at least one OID is required");
        }

        Set<String> names = new HashSet<>();

        // 그룹 공통 OID 설정은 한 번만 검사한다.
        for (int index = 0; index < spec.oids().size(); index++) {
            CollectionGroupOidSpec oid = spec.oids().get(index);
            String path = "oids[" + index + "]";

            validateOid(oid, path);

            if (!names.add(oid.name())) {
                throw invalid(path, "duplicate point name: " + oid.name());
            }
        }

        // 장비마다 instanceId가 다를 수 있으므로
        // 실제 OID 생성 검사는 target별로 수행한다.
        for (int targetIndex = 0; targetIndex < spec.targets().size(); targetIndex++) {

            SnmpCollectionTargetSpec target = spec.targets().get(targetIndex);
            String targetPath = "targets[" + targetIndex + "]";

            validateTarget(target, targetPath);

            for (int oidIndex = 0; oidIndex < spec.oids().size(); oidIndex++) {

                CollectionGroupOidSpec oid = spec.oids().get(oidIndex);
                String path = targetPath + ".oids[" + oidIndex + "]";

                validateResolvedOid(oid, target, path);
            }
        }
    }

    private void validateTarget(
            SnmpCollectionTargetSpec target,
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

        if (target.instanceId() != null && target.instanceId() < 0) {
            throw invalid(path, "instanceId must not be negative");
        }
    }

    private void validateOid(CollectionGroupOidSpec oid, String path) {

        if (oid == null) {
            throw invalid(path, "OID is required");
        }

        validateName(oid.name(), path);

        if (oid.template() == null || oid.template().isBlank()) {
            throw invalid(path, "OID template is required");
        }

        boolean hasPlaceholder = oid.template().contains(INSTANCE_PLACEHOLDER);

        if (oid.requiresInstance() != hasPlaceholder) {
            throw invalid(path,
                    "requiresInstance must match the use of {instanceId}");
        }

        if (oid.scale() != null && !Double.isFinite(oid.scale())) {
            throw invalid(path, "scale must be finite");
        }
    }

    private void validateResolvedOid(CollectionGroupOidSpec oid, SnmpCollectionTargetSpec target, String path) {

        if (oid.requiresInstance() && target.instanceId() == null) {
            throw invalid(path, "instanceId is required for " + oid.name());
        }

        String resolved = oidTemplateResolver.resolve(oid, target);

        // 숫자를 점으로 구분한 형식인지 확인한다.
        // 맨 앞의 점은 허용한다.
        if (!resolved.matches("\\.?[0-9]+(?:\\.[0-9]+)+")) {
            throw invalid(path, "invalid numeric OID: " + resolved);
        }

        try {
            // 실제 통신과 같은 라이브러리로 해석 가능한지 확인한다.
            new OID(resolved);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    path + ": invalid OID: " + resolved,
                    exception
            );
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

    private IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }
}
package net.vivans.dcim.shared.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 기동 전에 필수 설정을 한 번에 검사한다.
 * 필수값이 하나라도 비어 있으면 첫 번째 누락값의 긴 스택트레이스 대신 누락된 환경변수 목록 전체를 보여주고 기동을 멈춘다.
 * 로컬 실행은 작업 폴더의 config/application.yml(git 제외)이나 IntelliJ 환경변수로 값을 넣는다.
 * 검사를 끄려면 STARTUP_REQUIRED_SETTINGS_ENABLED=false.
 */
public class RequiredSettingsValidator implements EnvironmentPostProcessor, Ordered {

    record Requirement(String property, String envHint, Predicate<Environment> when) {
        static Requirement always(String property, String envHint) {
            return new Requirement(property, envHint, environment -> true);
        }

        static Requirement when(String flagProperty, boolean flagDefault, String property, String envHint) {
            return new Requirement(property, envHint, environment -> flag(environment, flagProperty, flagDefault));
        }
    }

    static final List<Requirement> REQUIREMENTS = List.of(
            Requirement.when("security.auth-enabled", true, "security.api-key", "COLLECTOR_API_KEY"),
            Requirement.when("manager.service.enabled", true, "manager.service.api-key", "MANAGER_API_KEY"),
            Requirement.when("influx.enabled", true, "influx.url", "INFLUX_URL"),
            Requirement.when("influx.enabled", true, "influx.token", "INFLUX_TOKEN")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!flag(environment, "startup.required-settings.enabled", true)) {
            return;
        }
        List<Requirement> missing = missing(environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(message(missing));
        }
    }

    @Override
    public int getOrder() {
        // 설정 파일(application*.yml, config/application.yml)을 모두 읽은 뒤에 검사한다.
        return Ordered.LOWEST_PRECEDENCE;
    }

    static List<Requirement> missing(Environment environment) {
        return REQUIREMENTS.stream()
                .filter(requirement -> requirement.when().test(environment))
                .filter(requirement -> value(environment, requirement.property()).isEmpty())
                .toList();
    }

    static String message(List<Requirement> missing) {
        return "필수 설정이 비어 있어 기동을 중단합니다. 아래 환경변수(또는 설정)를 지정하세요:\n"
                + missing.stream()
                .map(requirement -> "  - " + requirement.envHint() + "  (" + requirement.property() + ")")
                .collect(Collectors.joining("\n"));
    }

    private static String value(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value == null ? "" : value.trim();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            // ${MARIADB_HOST}처럼 기본값 없는 자리표시자가 풀리지 않은 경우도 누락으로 본다.
            return "";
        }
    }

    private static boolean flag(Environment environment, String property, boolean defaultValue) {
        try {
            Boolean value = environment.getProperty(property, Boolean.class);
            return value == null ? defaultValue : value;
        } catch (RuntimeException invalid) {
            return defaultValue;
        }
    }
}

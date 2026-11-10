package net.vivans.dcim.module.ingest.manager.infrastructure;

/**
 * Manager에서 설정을 받아오지 못했음을 알린다.
 *
 * 조회 실패를 빈 목록으로 바꾸면 "설정이 비어 있다"와 구분할 수 없어
 * 정상 동작 중인 수집까지 중단된다. 호출 측은 이 예외를 받으면
 * 마지막으로 성공한 설정을 그대로 유지한다.
 */
public class ManagerConfigUnavailableException extends RuntimeException {

    private final String target;

    public ManagerConfigUnavailableException(String target, Throwable cause) {
        super("manager configuration unavailable: " + target, cause);
        this.target = target;
    }

    public String getTarget() {
        return target;
    }
}

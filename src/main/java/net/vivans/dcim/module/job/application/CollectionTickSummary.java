package net.vivans.dcim.module.job.application;

/**
 * 한 번의 tick 실행 결과 요약. {@code lastFailureReason}은 이번 tick에서 실패한 대상 중
 * 가장 마지막으로 실패한 대상의 사람이 읽을 수 있는 원인이다(실패가 없으면 null).
 */
record CollectionTickSummary(int total, int success, int failed, String lastFailureReason) {
}

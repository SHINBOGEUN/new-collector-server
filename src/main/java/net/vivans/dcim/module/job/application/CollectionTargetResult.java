package net.vivans.dcim.module.job.application;

/** 장비 한 대의 수집 결과. 공통 tick 실행기가 성공·실패를 집계할 때 사용합니다. */
public record CollectionTargetResult(boolean success, String reason) {
}

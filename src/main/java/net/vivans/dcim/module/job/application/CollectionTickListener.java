package net.vivans.dcim.module.job.application;

/**
 * 정기 수집 job의 tick이 끝날 때마다 결과를 통지받는다.
 * 프로토콜이 SNMP가 아니거나 대상이 없어 tick 자체가 실행되지 않은 경우에는 호출되지 않는다.
 */
@FunctionalInterface
interface CollectionTickListener {
    void onTickCompleted(CollectionTickSummary summary);
}

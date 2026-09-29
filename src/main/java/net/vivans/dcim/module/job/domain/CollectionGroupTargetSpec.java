package net.vivans.dcim.module.job.domain;

/** 결과 저장 대상과 접속 정보만 공통으로 노출한다. */
public interface CollectionGroupTargetSpec {
    Integer deviceId();
    String host();
    int port();
}

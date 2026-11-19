package net.vivans.dcim.module.job.application;
/** 한 대상의 결과. 부분 응답 값 수를 원본 장비 단위로 합산한다. */
public record CollectionTargetResult(boolean success,String reason,int valueCount,String failureCode) {
    public CollectionTargetResult(boolean success,String reason){this(success,reason,success?1:0,success?null:"ERROR");}
}

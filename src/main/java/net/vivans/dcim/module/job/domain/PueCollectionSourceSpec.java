package net.vivans.dcim.module.job.domain;
public record PueCollectionSourceSpec(Integer deviceId, String host, int port, String pointName, String oid, Double scale, String role) {}

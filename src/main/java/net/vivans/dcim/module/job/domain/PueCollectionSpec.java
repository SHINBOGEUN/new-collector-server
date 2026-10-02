package net.vivans.dcim.module.job.domain;
import java.util.List;
public record PueCollectionSpec(Integer calculatedMetricId, Integer configVersion, String cronExpression, String community,
                                int timeoutMs, int retries, List<PueCollectionSourceSpec> sources, String formula) { }

package net.vivans.dcim.module.job.domain;
import java.util.List;
public record PueCollectionSpec(Integer pueDefinitionId, Integer configVersion, String cronExpression, String community, int timeoutMs, int retries, List<PueCollectionSourceSpec> sources) {}

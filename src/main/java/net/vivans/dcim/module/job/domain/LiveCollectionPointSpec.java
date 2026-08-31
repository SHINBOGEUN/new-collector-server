package net.vivans.dcim.module.job.domain;

public record LiveCollectionPointSpec(
        String name,
        String template,
        boolean requiresInstance,
        Double scale,
        String unit
) {
}

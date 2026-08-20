package net.vivans.dcim.module.job.domain;

public record CollectionGroupOidSpec(
        String name,
        String template,
        boolean requiresInstance
) {
}

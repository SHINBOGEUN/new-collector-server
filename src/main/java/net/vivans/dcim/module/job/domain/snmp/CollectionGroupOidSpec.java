package net.vivans.dcim.module.job.domain.snmp;

public record CollectionGroupOidSpec(
        String name,
        String template,
        boolean requiresInstance,
        Double scale
) {
}

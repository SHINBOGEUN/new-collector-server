package net.vivans.dcim.module.snmp;

import java.util.List;
import java.util.Map;

public interface SnmpQueryClient {

    Map<String, Object> get(
            String host,
            int port,
            String community,
            int timeoutMs,
            int retries,
            List<OidQuery> oids
    );

    record OidQuery(String name, String oid) {
    }
}

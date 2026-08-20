package net.vivans.dcim.module.snmp;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UnsignedInteger32;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Profile("!test")
public class Snmp4jQueryClient implements SnmpQueryClient {

    private final Snmp snmp;

    public Snmp4jQueryClient() throws IOException {
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
        this.snmp = new Snmp(transport);
        transport.listen();
    }

    @Override
    public Map<String, Object> get(
            String host,
            int port,
            String community,
            int timeoutMs,
            int retries,
            List<OidQuery> oids
    ) {
        if (oids == null || oids.isEmpty()) {
            throw new IllegalArgumentException("조회할 OID가 없습니다.");
        }

        CommunityTarget<Address> target = new CommunityTarget<>();
        target.setCommunity(new OctetString(community == null || community.isBlank() ? "public" : community));
        Address address = GenericAddress.parse("udp:" + host + "/" + port);
        if (address == null) {
            throw new IllegalArgumentException("SNMP 주소를 만들 수 없습니다: " + host + ":" + port);
        }
        target.setAddress(address);
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(Math.max(timeoutMs, 1));
        target.setRetries(Math.max(retries, 0));

        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        for (OidQuery oid : oids) {
            pdu.add(new VariableBinding(new OID(oid.oid())));
        }

        try {
            ResponseEvent<Address> event = snmp.send(pdu, target);
            if (event == null || event.getResponse() == null) {
                throw new IOException("SNMP 응답이 없습니다: " + host + ":" + port);
            }
            PDU response = event.getResponse();
            if (response.getErrorStatus() != PDU.noError) {
                throw new IOException("SNMP 오류: " + response.getErrorStatusText());
            }
            return toValues(oids, response);
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private Map<String, Object> toValues(List<OidQuery> oids, PDU response) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<? extends VariableBinding> bindings = response.getVariableBindings();
        int size = Math.min(oids.size(), bindings.size());
        for (int i = 0; i < size; i++) {
            Variable variable = bindings.get(i).getVariable();
            if (variable == null || variable instanceof Null || variable.isException()) {
                continue;
            }
            Object parsed = parse(variable);
            if (parsed != null) {
                values.put(oids.get(i).name(), parsed);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("유효한 SNMP 값이 없습니다.");
        }
        return values;
    }

    private Object parse(Variable variable) {
        if (variable instanceof Integer32 integer32) {
            return integer32.getValue();
        }
        if (variable instanceof UnsignedInteger32 unsigned) {
            return unsigned.getValue();
        }
        if (variable instanceof org.snmp4j.smi.Counter64 counter64) {
            return counter64.getValue();
        }
        String text = variable.toString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    @PreDestroy
    public void close() {
        try {
            snmp.close();
        } catch (IOException ex) {
            log.warn("SNMP 세션 종료 실패: {}", ex.getMessage());
        }
    }
}

package net.vivans.dcim.module.ingest.lora.domain;

/** Manager 쪽 정규화 규칙과 반드시 동일해야 한다 (대문자 통일, 구분자 제거). */
public final class LoraExternalIdNormalizer {

    private LoraExternalIdNormalizer() {
    }

    public static String normalize(String externalId) {
        if (externalId == null) {
            return null;
        }
        return externalId.trim()
                .toUpperCase()
                .replace(":", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }
}

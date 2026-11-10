package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

import java.util.List;

public record LoraApiListResponse<T>(int status, List<T> data) {
}

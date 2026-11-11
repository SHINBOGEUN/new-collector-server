package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

public record LoraApiResponse<T>(int status, T data) {
}

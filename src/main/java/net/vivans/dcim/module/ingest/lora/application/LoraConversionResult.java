package net.vivans.dcim.module.ingest.lora.application;

public record LoraConversionResult(Status status, Double value) {

    public enum Status { OK, NO_DATA, FAILED }

    public static LoraConversionResult ok(double value) {
        return new LoraConversionResult(Status.OK, value);
    }

    public static LoraConversionResult noData() {
        return new LoraConversionResult(Status.NO_DATA, null);
    }

    public static LoraConversionResult failed() {
        return new LoraConversionResult(Status.FAILED, null);
    }
}

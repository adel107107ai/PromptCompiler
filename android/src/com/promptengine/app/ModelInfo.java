package com.promptengine.app;

/** One entry of the Google AI Studio model catalog. */
public final class ModelInfo {

    public final String id;
    public final String name;
    public final String category;
    public final Integer rpm;
    public final Integer tpm;
    public final Integer rpd;
    public final boolean generate;
    public final String note;

    public ModelInfo(String id, String name, String category, Integer rpm, Integer tpm,
                     Integer rpd, boolean generate, String note) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.rpm = rpm;
        this.tpm = tpm;
        this.rpd = rpd;
        this.generate = generate;
        this.note = note;
    }

    @Override
    public String toString() {
        return name;
    }
}

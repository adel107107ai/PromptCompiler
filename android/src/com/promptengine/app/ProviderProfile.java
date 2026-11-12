package com.promptengine.app;

import java.util.ArrayList;
import java.util.List;

/**
 * One AI backend. Built-in kinds cover Gemini, any OpenAI-compatible endpoint and Anthropic; a
 * fully custom provider is just an entry of one of those kinds with its own base URL and models.
 */
public final class ProviderProfile {

    public static final String KIND_GEMINI = "gemini";
    public static final String KIND_OPENAI = "openai";
    public static final String KIND_ANTHROPIC = "anthropic";

    public String id;
    public String name;
    public String kind = KIND_GEMINI;
    public String baseUrl = "";
    /** Tried in order: the next key takes over when one is rejected or out of quota. */
    public final List<String> apiKeys = new ArrayList<>();
    /** Fallback order: the next model is tried when one cannot answer. */
    public final List<String> models = new ArrayList<>();
    public boolean enabled = true;

    /**
     * Result of the last readiness test: 0 means it was never proven. The green badge must mean
     * "a real request succeeded", never merely "the fields are non-empty".
     */
    public long lastProbeAt;
    public int lastProbeReady;
    public int lastProbeTotal;

    public ProviderProfile(String id, String name, String kind, String baseUrl) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.baseUrl = baseUrl;
    }

    public boolean usable() {
        return enabled && !apiKeys.isEmpty() && !models.isEmpty();
    }

    public int keyCount() {
        return apiKeys.size();
    }

    public String normalizedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            return defaultBaseUrl(kind);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static String defaultBaseUrl(String kind) {
        if (KIND_OPENAI.equals(kind)) {
            return "https://api.openai.com/v1";
        }
        if (KIND_ANTHROPIC.equals(kind)) {
            return "https://api.anthropic.com/v1";
        }
        return ModelCatalog.DEFAULT_BASE_URL;
    }

    public static String kindLabel(String kind) {
        if (KIND_OPENAI.equals(kind)) {
            return "OpenAI-compatible";
        }
        if (KIND_ANTHROPIC.equals(kind)) {
            return "Anthropic";
        }
        return "Google Gemini";
    }

    /** The kinds a user can pick, in the order the editor shows them. */
    public static String[] kinds() {
        return new String[]{KIND_GEMINI, KIND_OPENAI, KIND_ANTHROPIC};
    }

    @Override
    public String toString() {
        return name;
    }
}

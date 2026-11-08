package com.promptengine.app;

/** Persisted application settings. */
public final class Config {

    public String apiKey = "";
    public String model = ModelCatalog.DEFAULT_MODEL;
    public String systemPromptOverride = "";
    public String baseUrl = ModelCatalog.DEFAULT_BASE_URL;

    /** The override wins when it has any non-empty content, exactly like the Python service. */
    public String effectiveSystemPrompt() {
        if (systemPromptOverride != null && systemPromptOverride.trim().length() > 0) {
            return systemPromptOverride;
        }
        return PromptEngine.SYSTEM_PROMPT;
    }

    public String baseUrlOr() {
        if (baseUrl == null || baseUrl.trim().length() == 0) {
            return ModelCatalog.DEFAULT_BASE_URL;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}

package com.promptengine.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google AI Studio model catalog.
 *
 * The built-in table is an offline snapshot (ported from prompt_compiler/catalog.py) so the
 * picker works without network. Settings can merge the live list returned by models.list on
 * top of it, which is what keeps the snapshot from going stale.
 */
public final class ModelCatalog {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String TEXT_CATEGORY = "Text-out models";

    private static final Integer N = null;

    private static ModelInfo text(String id, String name, Integer rpm, Integer tpm, Integer rpd) {
        return new ModelInfo(id, name, TEXT_CATEGORY, rpm, tpm, rpd, true, null);
    }

    private static ModelInfo other(String id, String name, String category, Integer rpm, Integer tpm, Integer rpd) {
        return new ModelInfo(id, name, category, rpm, tpm, rpd, false, null);
    }

    public static final ModelInfo[] BUILT_IN = {
            text("gemini-3.5-flash", "Gemini 3.5 Flash", 5, 250000, 20),
            text("gemini-3.8-flash", "Gemini 3.8 Flash", 5, 250000, 20),
            text("gemini-3.7-flash", "Gemini 3.7 Flash", 5, 250000, 20),
            text("gemini-3.6-flash", "Gemini 3.6 Flash", 5, 250000, 20),
            text("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", 15, 250000, 500),
            text("gemini-3.1-pro", "Gemini 3.1 Pro", N, N, N),
            text("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", 15, 250000, 500),
            text("gemini-3-flash", "Gemini 3 Flash", 5, 250000, 20),
            text("gemini-2.5-pro", "Gemini 2.5 Pro", N, N, N),
            text("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", 10, 250000, 20),
            text("gemini-2.5-flash", "Gemini 2.5 Flash", 5, 250000, 20),
            text("gemini-2.0-flash-lite", "Gemini 2 Flash Lite", N, N, N),
            text("gemini-2.0-flash", "Gemini 2 Flash", N, N, N),

            other("antigravity", "Antigravity", "Agents", 60, 100000, 100),
            other("deep-research-pro", "Deep Research Pro Preview", "Agents", N, N, N),
            other("computer-use-preview", "Computer Use Preview", "Other models", N, N, N),
            other("gemini-embedding-001", "Gemini Embedding 1", "Other models", 100, 30000, 1000),
            other("gemini-embedding-2", "Gemini Embedding 2", "Other models", 100, 30000, 1000),
            other("gemini-robotics-er-2-preview", "Gemini Robotics ER 2 Preview", "Other models", 5, 250000, 20),
            other("gemma-4-26b", "Gemma 4 26B", "Other models", 30, 16000, 14400),
            other("gemma-4-31b", "Gemma 4 31B", "Other models", 30, 16000, 14400),
            other("gemini-2.5-flash-preview-image", "Nano Banana (Gemini 2.5 Flash Preview Image)", "Multi-modal generative models", N, N, N),
            other("gemini-2.5-flash-tts", "Gemini 2.5 Flash TTS", "Multi-modal generative models", 3, 10000, 10),
            other("gemini-2.5-pro-tts", "Gemini 2.5 Pro TTS", "Multi-modal generative models", N, N, N),
            other("gemini-3-pro-image", "Nano Banana Pro (Gemini 3 Pro Image)", "Multi-modal generative models", N, N, N),
            other("gemini-3.1-flash", "Nano Banana 2 (Gemini 3.1 Flash Image)", "Multi-modal generative models", N, N, N),
            other("gemini-3.1-flash-lite-image", "Nano Banana 2 Lite (Gemini 3.1 Flash Lite Image)", "Multi-modal generative models", N, N, N),
            other("gemini-3.1-flash-tts", "Gemini 3.1 Flash TTS", "Multi-modal generative models", 3, 10000, 10),
            other("gemini-omni-1.1-flash", "Gemini Omni 1.1 Flash", "Multi-modal generative models", N, N, N),
            other("gemini-omni-flash", "Gemini Omni Flash", "Multi-modal generative models", N, N, N),
            other("lyria-3-clip", "Lyria 3 Clip", "Multi-modal generative models", N, N, N),
            other("lyria-3-pro", "Lyria 3 Pro", "Multi-modal generative models", N, N, N),
            other("veo-3-fast-generate", "Veo 3 Fast Generate", "Multi-modal generative models", N, N, N),
            other("veo-3-generate", "Veo 3 Generate", "Multi-modal generative models", N, N, N),
            other("veo-3-lite-generate", "Veo 3 Lite Generate", "Multi-modal generative models", N, N, N),
            other("gemini-2.5-flash-native-audio-dialog", "Gemini 2.5 Flash Native Audio Dialog", "Live API", N, 1000000, N),
            other("gemini-3-flash-live", "Gemini 3 Flash Live", "Live API", N, 65000, N),
            other("gemini-3.5-live-translate", "Gemini 3.5 Live Translate", "Live API", N, 20000, N),
            other("gemini-3.5-transcribe", "Gemini 3.5 Transcribe", "Live API", 3, 10000, 25),
            other("gemini-3.5-transcribe-live", "Gemini 3.5 Transcribe Live", "Live API", N, 20000, N),
            other("gemini-3.8-live", "Gemini 3.8 Live", "Live API", N, 65000, N),
            other("gemini-3.8-live-extended-thinking", "Gemini 3.8 Live Extended Thinking", "Live API", N, 65000, N),
    };

    private ModelCatalog() {
    }

    /** Built-in snapshot merged with whatever models.list reported, built-ins first. */
    public static List<ModelInfo> all(Store store) {
        List<ModelInfo> models = new ArrayList<>();
        Map<String, ModelInfo> seen = new LinkedHashMap<>();
        for (ModelInfo model : BUILT_IN) {
            models.add(model);
            seen.put(model.id, model);
        }
        for (ModelInfo live : store.loadLiveModels()) {
            if (!seen.containsKey(live.id)) {
                models.add(live);
                seen.put(live.id, live);
            }
        }
        return models;
    }

    public static ModelInfo find(String id) {
        if (id == null) {
            return null;
        }
        for (ModelInfo model : BUILT_IN) {
            if (model.id.equals(id)) {
                return model;
            }
        }
        return null;
    }

    /** Display name for an id that is not in the built-in table. */
    public static String nameFor(String id) {
        ModelInfo model = find(id);
        if (model != null) {
            return model.name;
        }
        return id == null ? "" : id;
    }

    public static String categoryLabel(String category) {
        switch (category == null ? "" : category) {
            case "coding": return "💻 برنامه‌نویسی";
            case "debugging": return "🐛 دیباگ";
            case "research": return "📚 ریسرچ";
            case "writing": return "✍️ نوشتن";
            case "translation": return "🔁 ترجمه";
            case "data_analysis": return "📊 تحلیل داده";
            case "planning": return "🗺️ برنامه‌ریزی";
            case "automation": return "🤖 اتوماسیون";
            case "image_generation": return "🖼️ ساخت تصویر";
            default: return "📋 عمومی";
        }
    }
}

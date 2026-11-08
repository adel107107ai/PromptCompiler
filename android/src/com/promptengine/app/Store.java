package com.promptengine.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Settings, token-usage events, the request memory and the last session. */
public final class Store {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String PREFS = "prompt_engine";
    private static final String KEY_API = "api_key_sealed";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt_override";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_LIVE_MODELS = "live_models";
    private static final String KEY_LAST_INPUT = "last_input";
    private static final String KEY_LAST_RAW = "last_raw";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LAST_AT = "last_at";

    private static final String USAGE_FILE = "usage.json";
    private static final String HISTORY_FILE = "history.json";

    private static final long RETENTION_MS = 7L * 24L * 3600L * 1000L;

    /** How many past requests the memory keeps. */
    public static final int HISTORY_LIMIT = 100;

    /** History and usage files are read-modify-written as a whole, so serialise access. */
    private static final Object HISTORY_LOCK = new Object();
    private static final Object USAGE_LOCK = new Object();

    private final Context context;

    public Store(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- settings

    public Config loadConfig() {
        Config config = new Config();
        try {
            SharedPreferences prefs = prefs();
            config.apiKey = Crypto.open(prefs.getString(KEY_API, ""));
            config.model = prefs.getString(KEY_MODEL, ModelCatalog.DEFAULT_MODEL);
            config.systemPromptOverride = prefs.getString(KEY_SYSTEM_PROMPT, "");
            config.baseUrl = prefs.getString(KEY_BASE_URL, ModelCatalog.DEFAULT_BASE_URL);
        } catch (Throwable ignored) {
            // fall back to defaults
        }
        if (config.model == null || config.model.trim().isEmpty()) {
            config.model = ModelCatalog.DEFAULT_MODEL;
        }
        return config;
    }

    public void saveConfig(Config config) {
        try {
            prefs().edit()
                    .putString(KEY_API, Crypto.seal(config.apiKey == null ? "" : config.apiKey.trim()))
                    .putString(KEY_MODEL, config.model)
                    .putString(KEY_SYSTEM_PROMPT, config.systemPromptOverride == null ? "" : config.systemPromptOverride)
                    .putString(KEY_BASE_URL, config.baseUrlOr())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Masked form for display, so the key is never echoed back into the UI in full. */
    public static String mask(String key) {
        if (key == null || key.length() < 8) {
            return key == null ? "" : key;
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    // ------------------------------------------------------------ live models

    public List<ModelInfo> loadLiveModels() {
        List<ModelInfo> models = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_LIVE_MODELS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                models.add(new ModelInfo(id, item.optString("name", id),
                        ModelCatalog.TEXT_CATEGORY, null, null, null, true, null));
            }
        } catch (Throwable ignored) {
        }
        return models;
    }

    public void saveLiveModels(List<ModelInfo> models) {
        try {
            JSONArray array = new JSONArray();
            for (ModelInfo model : models) {
                JSONObject item = new JSONObject();
                item.put("id", model.id);
                item.put("name", model.name);
                array.put(item);
            }
            prefs().edit().putString(KEY_LIVE_MODELS, array.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------- token usage

    public JSONArray loadUsage() {
        try {
            String raw = readFile(USAGE_FILE);
            if (raw != null && !raw.trim().isEmpty()) {
                JSONArray events = new JSONObject(raw).optJSONArray("events");
                if (events != null) {
                    return events;
                }
            }
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    public void recordUsage(String modelId, JSONObject usageMetadata) {
        if (usageMetadata == null || usageMetadata.length() == 0) {
            return;
        }
        synchronized (USAGE_LOCK) {
            try {
                JSONArray events = loadUsage();
                JSONObject event = new JSONObject();
                event.put("m", modelId);
                event.put("t", System.currentTimeMillis());
                event.put("in", usageMetadata.optInt("promptTokenCount", 0));
                event.put("out", usageMetadata.optInt("candidatesTokenCount", 0));
                event.put("th", usageMetadata.optInt("thoughtsTokenCount", 0));
                event.put("tot", usageMetadata.optInt("totalTokenCount", 0));

                long cutoff = System.currentTimeMillis() - RETENTION_MS;
                JSONArray kept = new JSONArray();
                for (int index = 0; index < events.length(); index++) {
                    JSONObject existing = events.optJSONObject(index);
                    if (existing != null && existing.optLong("t", 0) >= cutoff) {
                        kept.put(existing);
                    }
                }
                kept.put(event);

                JSONObject root = new JSONObject();
                root.put("events", kept);
                writeFile(USAGE_FILE, root.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------- request memory (history)

    /** Newest first. Each entry: t, model, input, raw, cat, purpose. */
    public JSONArray loadHistory() {
        synchronized (HISTORY_LOCK) {
            return loadHistoryLocked();
        }
    }

    private JSONArray loadHistoryLocked() {
        try {
            String raw = readFile(HISTORY_FILE);
            if (raw != null && !raw.trim().isEmpty()) {
                return new JSONArray(raw);
            }
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    public void addHistory(String modelId, String input, String rawResponse,
                           String category, String purpose) {
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray existing = loadHistoryLocked();
                JSONArray next = new JSONArray();
                JSONObject entry = new JSONObject();
                entry.put("t", System.currentTimeMillis());
                entry.put("model", modelId == null ? "" : modelId);
                entry.put("input", input == null ? "" : input);
                entry.put("raw", rawResponse == null ? "" : rawResponse);
                entry.put("cat", category == null ? "" : category);
                entry.put("purpose", purpose == null ? "" : purpose);
                next.put(entry);
                for (int index = 0; index < existing.length() && next.length() < HISTORY_LIMIT; index++) {
                    JSONObject item = existing.optJSONObject(index);
                    if (item != null) {
                        next.put(item);
                    }
                }
                writeFile(HISTORY_FILE, next.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    /** The stored request whose timestamp matches, or null. */
    public JSONObject findHistory(long timestamp) {
        JSONArray entries = loadHistory();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null && entry.optLong("t", 0) == timestamp) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Removes one entry. The timestamp alone is not unique when two requests land in the
     * same millisecond, so the caller passes the input text as a tie breaker.
     */
    public void deleteHistory(long timestamp, String input) {
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray existing = loadHistoryLocked();
                JSONArray next = new JSONArray();
                boolean removed = false;
                for (int index = 0; index < existing.length(); index++) {
                    JSONObject item = existing.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    boolean match = !removed
                            && item.optLong("t", 0) == timestamp
                            && item.optString("input", "").equals(input == null ? "" : input);
                    if (match) {
                        removed = true;
                        continue;
                    }
                    next.put(item);
                }
                writeFile(HISTORY_FILE, next.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    public void clearHistory() {
        synchronized (HISTORY_LOCK) {
            try {
                writeFile(HISTORY_FILE, "[]");
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ last session

    /** Keeps the screen the user left, so reopening the app resumes where it stopped. */
    public void saveLastSession(String input, String rawResponse, String model) {
        try {
            prefs().edit()
                    .putString(KEY_LAST_INPUT, input == null ? "" : input)
                    .putString(KEY_LAST_RAW, rawResponse == null ? "" : rawResponse)
                    .putString(KEY_LAST_MODEL, model == null ? "" : model)
                    .putLong(KEY_LAST_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    public JSONObject loadLastSession() {
        try {
            SharedPreferences prefs = prefs();
            String raw = prefs.getString(KEY_LAST_RAW, "");
            String input = prefs.getString(KEY_LAST_INPUT, "");
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            JSONObject session = new JSONObject();
            session.put("input", input == null ? "" : input);
            session.put("raw", raw);
            session.put("model", prefs.getString(KEY_LAST_MODEL, ""));
            session.put("t", prefs.getLong(KEY_LAST_AT, 0L));
            return session;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void clearLastSession() {
        try {
            prefs().edit()
                    .remove(KEY_LAST_INPUT)
                    .remove(KEY_LAST_RAW)
                    .remove(KEY_LAST_MODEL)
                    .remove(KEY_LAST_AT)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- io

    private String readFile(String name) {
        File file = new File(context.getFilesDir(), name);
        if (!file.exists()) {
            return null;
        }
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return new String(buffer.toByteArray(), UTF8);
            } finally {
                input.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeFile(String name, String content) {
        try {
            FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name));
            try {
                output.write(content.getBytes(UTF8));
                output.flush();
            } finally {
                output.close();
            }
        } catch (Throwable ignored) {
        }
    }
}

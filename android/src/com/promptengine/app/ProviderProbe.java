package com.promptengine.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a provider before it is trusted: which models the service offers, and whether each model
 * actually answers a real request.
 */
final class ProviderProbe {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PING_TEXT = "Reply with the single word OK.";
    /**
     * Enough room for the answer AND the model's internal thinking: a thinking model given a
     * tiny budget spends it all before emitting any text and would look broken while healthy.
     */
    private static final int PING_MAX_TOKENS = 256;

    private ProviderProbe() {
    }

    /** The model ids the service lists for this key. Empty when the service has no such endpoint. */
    static List<String> fetchModels(String kind, String base, String key) {
        List<String> models = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            String endpoint = ProviderProfile.KIND_OPENAI.equals(kind)
                    ? base + "/models"
                    : base + "/v1beta/models?pageSize=200";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            } else {
                connection.setRequestProperty("x-goog-api-key", key);
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                readAll(connection.getErrorStream());
                return models;
            }
            String body = readAll(connection.getInputStream());
            JSONArray entries = ProviderProfile.KIND_OPENAI.equals(kind)
                    ? new JSONObject(body).optJSONArray("data")
                    : new JSONObject(body).optJSONArray("models");
            if (entries == null) {
                return models;
            }
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String id = entry.optString("id", entry.optString("name", ""));
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (id.isEmpty() || models.contains(id)) {
                    continue;
                }
                // For Gemini, only models that can generate content are useful here.
                if (ProviderProfile.KIND_GEMINI.equals(kind)) {
                    JSONArray methods = entry.optJSONArray("supportedGenerationMethods");
                    boolean generates = false;
                    if (methods != null) {
                        for (int m = 0; m < methods.length(); m++) {
                            if ("generateContent".equals(methods.optString(m))) {
                                generates = true;
                                break;
                            }
                        }
                    }
                    if (!generates) {
                        continue;
                    }
                }
                models.add(id);
            }
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return models;
    }

    /** Result of one real request against one model. */
    static final class Ping {
        final boolean ok;
        final long millis;
        final String detail;

        Ping(boolean ok, long millis, String detail) {
            this.ok = ok;
            this.millis = millis;
            this.detail = detail;
        }
    }

    /** Sends the smallest possible real request so a model can be proven ready or rejected. */
    static Ping ping(String kind, String base, String key, String model) {
        HttpURLConnection connection = null;
        long started = System.currentTimeMillis();
        try {
            String endpoint;
            String body;
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                endpoint = base + "/chat/completions";
                body = new JSONObject()
                        .put("model", model)
                        .put("max_tokens", PING_MAX_TOKENS)
                        .put("messages", new JSONArray().put(
                                new JSONObject().put("role", "user").put("content", PING_TEXT)))
                        .toString();
            } else if (ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
                endpoint = base + "/messages";
                body = new JSONObject()
                        .put("model", model)
                        .put("max_tokens", PING_MAX_TOKENS)
                        .put("messages", new JSONArray().put(
                                new JSONObject().put("role", "user").put("content", PING_TEXT)))
                        .toString();
            } else {
                endpoint = base + "/v1beta/models/" + model + ":generateContent";
                body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject()
                                .put("role", "user")
                                .put("parts", new JSONArray().put(
                                        new JSONObject().put("text", PING_TEXT)))))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", PING_MAX_TOKENS))
                        .toString();
            }

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            } else if (ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
                connection.setRequestProperty("x-api-key", key);
                connection.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                connection.setRequestProperty("x-goog-api-key", key);
            }

            byte[] payload = body.getBytes(UTF8);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            long millis = System.currentTimeMillis() - started;
            String response = status == 200
                    ? readAll(connection.getInputStream())
                    : readAll(connection.getErrorStream());
            if (status != 200) {
                return new Ping(false, millis, "HTTP " + status + " — " + trim(extractMessage(response)));
            }
            if (response.isEmpty()) {
                return new Ping(false, millis, "empty response");
            }
            // HTTP 200 with no text means the budget went to internal thinking: report it as a
            // real failure with the reason instead of a silent pass.
            if (!hasAnyText(kind, response)) {
                return new Ping(false, millis, "answered without text (thinking budget)");
            }
            return new Ping(true, millis, "ok");
        } catch (Throwable failure) {
            long millis = System.currentTimeMillis() - started;
            return new Ping(false, millis,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** True when the response actually carries answer text rather than an empty candidate. */
    private static boolean hasAnyText(String kind, String response) {
        try {
            JSONObject root = new JSONObject(response);
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                JSONArray choices = root.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    return false;
                }
                JSONObject message = choices.optJSONObject(0).optJSONObject("message");
                return message != null && !message.optString("content", "").trim().isEmpty();
            }
            if (ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
                JSONArray blocks = root.optJSONArray("content");
                if (blocks == null) {
                    return false;
                }
                for (int index = 0; index < blocks.length(); index++) {
                    JSONObject block = blocks.optJSONObject(index);
                    if (block != null && !block.optString("text", "").trim().isEmpty()) {
                        return true;
                    }
                }
                return false;
            }
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return false;
            }
            JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) {
                return false;
            }
            for (int index = 0; index < parts.length(); index++) {
                JSONObject part = parts.optJSONObject(index);
                if (part != null && !part.optString("text", "").trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String extractMessage(String body) {
        if (body == null) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                return error.optString("message", "");
            }
            return root.optString("message", "");
        } catch (Throwable ignored) {
            return body;
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        } catch (Throwable ignored) {
            return "";
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

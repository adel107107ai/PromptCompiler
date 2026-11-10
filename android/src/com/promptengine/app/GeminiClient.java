package com.promptengine.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Google AI Studio (Gemini) client.
 *
 * Differences from the Python service, on purpose:
 *  - the key travels in the x-goog-api-key header, never in the query string;
 *  - responses are streamed with SSE so the prompt appears while it is still being written;
 *  - failures are reported as stable kinds so the UI owns the wording.
 */
public final class GeminiClient {

    private static final String TAG = "PromptEngine";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final int ERR_HTTP = 1;
    public static final int ERR_BLOCKED = 2;
    public static final int ERR_RATE_LIMIT = 3;
    public static final int ERR_OFFLINE = 4;
    public static final int ERR_MODEL = 5;
    public static final int ERR_KEY = 6;
    public static final int ERR_EMPTY = 7;
    public static final int ERR_CANCELLED = 8;

    /** Anything that can report a classified failure. */
    public interface FailureCallback {
        void onFailure(int kind, String detail);
    }

    public interface CompileCallback extends FailureCallback {
        void onProgress(String rawSoFar);

        void onSuccess(String rawText, JSONObject usageMetadata);
    }

    /** The discovery phase hands back the whole parsed result at once; it is short and non-streamed. */
    public interface ClarifyCallback extends FailureCallback {
        void onReady(ClarifyResult result, String rawText, JSONObject usageMetadata);
    }

    public interface ModelsCallback extends FailureCallback {
        void onModels(List<ModelInfo> models);
    }

    /** Handle that lets the UI abandon an in-flight request. */
    public static final class Job {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private GeminiClient() {
    }

    public static Job compile(final Config config, final String persianInput, final CompileCallback callback) {
        return compile(config, persianInput, null, callback);
    }

    /** The full compile: request + answers collected by the discovery phase. */
    public static Job compile(final Config config, final String persianInput,
                              final List<String[]> answers, final CompileCallback callback) {
        final Job job = new Job();
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            callback.onFailure(ERR_KEY, "");
            return job;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                streamCompile(job, config, persianInput, answers, callback);
            }
        }, "gemini-compile").start();
        return job;
    }

    /** Runs the discovery phase: what is missing, and what is worth asking. */
    public static void clarify(final Config config, final String persianInput,
                               final ClarifyCallback callback) {
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            callback.onFailure(ERR_KEY, "");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                runClarify(config, persianInput, callback);
            }
        }, "gemini-clarify").start();
    }

    public static void listModels(final Config config, final ModelsCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                fetchModels(config, callback);
            }
        }, "gemini-models").start();
    }

    // ------------------------------------------------------------- compiling

    private static void streamCompile(Job job, Config config, String persianInput,
                                      List<String[]> answers, CompileCallback callback) {
        HttpURLConnection connection = null;
        try {
            String endpoint = config.baseUrlOr()
                    + "/v1beta/models/" + config.model
                    + ":streamGenerateContent?alt=sse";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            job.connection = connection;
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", config.apiKey.trim());
            connection.setRequestProperty("Accept", "text/event-stream");

            byte[] body = buildRequestBody(config, persianInput, answers).getBytes(UTF8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                String errorBody = readAll(connection.getErrorStream());
                if (job.isCancelled()) {
                    postFailure(callback, ERR_CANCELLED, "");
                    return;
                }
                reportHttpFailure(callback, status, errorBody);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF8));
            StringBuilder raw = new StringBuilder();
            JSONObject usage = null;
            long lastPost = 0L;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONObject chunkUsage = chunk.optJSONObject("usageMetadata");
                    if (chunkUsage != null) {
                        usage = chunkUsage;
                    }
                    JSONArray candidates = chunk.optJSONArray("candidates");
                    if (candidates == null || candidates.length() == 0) {
                        continue;
                    }
                    JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
                    if (content == null) {
                        continue;
                    }
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts == null) {
                        continue;
                    }
                    for (int index = 0; index < parts.length(); index++) {
                        JSONObject part = parts.optJSONObject(index);
                        if (part != null) {
                            raw.append(part.optString("text", ""));
                        }
                    }
                } catch (Throwable malformed) {
                    Log.w(TAG, "skipping unparsable SSE chunk", malformed);
                }

                long now = System.currentTimeMillis();
                if (now - lastPost > 90L) {
                    lastPost = now;
                    final String snapshot = raw.toString();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onProgress(snapshot);
                        }
                    });
                }
            }
            reader.close();

            final String text = raw.toString();
            if (text.trim().isEmpty()) {
                postFailure(callback, ERR_EMPTY, "");
                return;
            }
            final JSONObject finalUsage = usage;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(text, finalUsage);
                }
            });
        } catch (final Throwable failure) {
            if (job.isCancelled()) {
                postFailure(callback, ERR_CANCELLED, "");
                return;
            }
            Log.w(TAG, "compile failed", failure);
            int kind = offlineKind(failure);
            postFailure(callback, kind, describe(failure));
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String buildRequestBody(Config config, String persianInput,
                                           List<String[]> answers) throws Exception {
        JSONObject part = new JSONObject().put("text", config.effectiveSystemPrompt());
        JSONObject systemInstruction = new JSONObject().put("parts", new JSONArray().put(part));

        JSONObject userPart = new JSONObject().put("text",
                PromptEngine.buildUserPrompt(persianInput, answers));
        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        JSONObject generationConfig = new JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", 8192);

        return new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(userContent))
                .put("generationConfig", generationConfig)
                .toString();
    }

    // ------------------------------------------------------------- discovery

    private static void runClarify(Config config, String persianInput, ClarifyCallback callback) {
        HttpURLConnection connection = null;
        try {
            // The interview analysis pass runs on the light model: fast, and a small budget.
            String analysisModel = ModelCatalog.find(ModelCatalog.ANALYSIS_LIGHT_MODEL) != null
                    ? ModelCatalog.ANALYSIS_LIGHT_MODEL : config.model;
            String endpoint = config.baseUrlOr()
                    + "/v1beta/models/" + analysisModel + ":generateContent";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", config.apiKey.trim());

            byte[] body = buildClarifyBody(persianInput).getBytes(UTF8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                reportHttpFailure(callback, status, readAll(connection.getErrorStream()));
                return;
            }

            JSONObject root = new JSONObject(readAll(connection.getInputStream()));
            String text = firstCandidateText(root);
            if (text == null || text.trim().isEmpty()) {
                postFailure(callback, ERR_EMPTY, "");
                return;
            }

            final String raw = text;
            final JSONObject usage = root.optJSONObject("usageMetadata");
            final ClarifyResult parsed = PromptEngine.parseClarify(raw);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    callback.onReady(parsed, raw, usage);
                }
            });
        } catch (Throwable failure) {
            Log.w(TAG, "clarify failed", failure);
            postFailure(callback, offlineKind(failure), describe(failure));
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String buildClarifyBody(String persianInput) throws Exception {
        JSONObject systemPart = new JSONObject().put("text", SystemPrompt.INTERVIEW_ANALYSIS_PROMPT);
        JSONObject systemInstruction = new JSONObject().put("parts", new JSONArray().put(systemPart));

        JSONObject userPart = new JSONObject().put("text", persianInput);
        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        // Light analysis budget: the gate returns key fields only, never a drafted prompt.
        JSONObject generationConfig = new JSONObject()
                .put("temperature", 0.4)
                .put("maxOutputTokens", 800);

        return new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(userContent))
                .put("generationConfig", generationConfig)
                .toString();
    }

    private static String firstCandidateText(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        if (content == null) {
            return null;
        }
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.optJSONObject(index);
            if (part != null) {
                text.append(part.optString("text", ""));
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    // ------------------------------------------------------------- error map

    private static void reportHttpFailure(FailureCallback callback, int status, String errorBody) {
        String detail = extractMessage(errorBody);
        if (looksLikeHtml(errorBody)) {
            postFailure(callback, ERR_BLOCKED, "");
            return;
        }
        if (status == 400 && detail.toLowerCase().contains("api key")) {
            postFailure(callback, ERR_KEY, detail);
            return;
        }
        if (status == 401 || status == 403) {
            postFailure(callback, ERR_KEY, detail);
            return;
        }
        if (status == 404) {
            postFailure(callback, ERR_MODEL, detail);
            return;
        }
        if (status == 429) {
            postFailure(callback, ERR_RATE_LIMIT, detail);
            return;
        }
        postFailure(callback, ERR_HTTP, "[" + status + "] " + detail);
    }

    private static void postFailure(final FailureCallback callback, final int kind, final String detail) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onFailure(kind, detail);
            }
        });
    }

    private static int offlineKind(Throwable failure) {
        if (failure instanceof UnknownHostException) {
            return ERR_OFFLINE;
        }
        if (failure instanceof SocketTimeoutException) {
            return ERR_HTTP;
        }
        String name = failure.getClass().getSimpleName().toLowerCase();
        if (name.contains("ssl") || name.contains("connect") || name.contains("servicenot")) {
            return ERR_OFFLINE;
        }
        return ERR_HTTP;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String name = failure.getClass().getSimpleName();
        return message == null || message.trim().isEmpty() ? name : name + ": " + message;
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String head = body.trim().toLowerCase();
        return head.startsWith("<!doctype") || head.startsWith("<html");
    }

    private static String extractMessage(String errorBody) {
        if (errorBody == null) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(errorBody);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return message;
                }
            }
        } catch (Throwable ignored) {
        }
        String trimmed = errorBody.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
    }

    // --------------------------------------------------------- model listing

    private static void fetchModels(Config config, ModelsCallback callback) {
        HttpURLConnection connection = null;
        try {
            String endpoint = config.baseUrlOr() + "/v1beta/models?pageSize=200";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("x-goog-api-key", config.apiKey.trim());

            int status = connection.getResponseCode();
            String body = status == 200
                    ? readAll(connection.getInputStream())
                    : readAll(connection.getErrorStream());

            if (status != 200) {
                final int kind = looksLikeHtml(body) ? ERR_BLOCKED
                        : (status == 401 || status == 403 || status == 400) ? ERR_KEY : ERR_HTTP;
                final String detail = extractMessage(body);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onFailure(kind, detail);
                    }
                });
                return;
            }

            final List<ModelInfo> models = parseModels(body);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    callback.onModels(models);
                }
            });
        } catch (Throwable failure) {
            Log.w(TAG, "listModels failed", failure);
            final int kind = offlineKind(failure);
            final String detail = describe(failure);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(kind, detail);
                }
            });
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<ModelInfo> parseModels(String body) {
        List<ModelInfo> models = new ArrayList<>();
        try {
            JSONArray entries = new JSONObject(body).optJSONArray("models");
            if (entries == null) {
                return models;
            }
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                JSONArray methods = entry.optJSONArray("supportedGenerationMethods");
                boolean canGenerate = false;
                if (methods != null) {
                    for (int method = 0; method < methods.length(); method++) {
                        if ("generateContent".equals(methods.optString(method))) {
                            canGenerate = true;
                            break;
                        }
                    }
                }
                if (!canGenerate) {
                    continue;
                }
                String id = entry.optString("name", "");
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (id.isEmpty()) {
                    continue;
                }
                String display = entry.optString("displayName", id);
                models.add(new ModelInfo(id, display, ModelCatalog.TEXT_CATEGORY, null, null, null, true, null));
            }
        } catch (Throwable ignored) {
        }
        return models;
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

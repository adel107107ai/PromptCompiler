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
import java.util.List;

/**
 * Multi-provider AI client.
 *
 * A request is attempted over an ordered list of candidates (provider, API key, model). A
 * rejected key, an exhausted quota, an unknown model or a blocked endpoint moves straight on to
 * the next candidate, so the tool keeps answering as long as one provider/key/model combination
 * still works.
 *
 * Supported kinds: Google Gemini, any OpenAI-compatible endpoint, and Anthropic.
 */
public final class AiClient {

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

    public interface CompileCallback {
        void onProgress(String rawSoFar, String source);

        void onSuccess(String rawText, JSONObject usageMetadata, String source);

        void onFailure(int kind, String detail);
    }

    public interface AnalyzeCallback {
        void onReady(ClarifyResult result, String rawText, JSONObject usageMetadata, String source);

        void onFailure(int kind, String detail);
    }

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

    private AiClient() {
    }

    // ------------------------------------------------------------- entry points

    public static Job compile(Config config, Store store, String input, List<String[]> answers,
                              CompileCallback callback) {
        final Job job = new Job();
        final List<Providers.Candidate> candidates = Providers.candidates(
                Providers.load(store, config.apiKey), config.model);
        if (candidates.isEmpty()) {
            callback.onFailure(ERR_KEY, "");
            return job;
        }
        final String userPrompt = PromptEngine.buildUserPrompt(input, answers);
        final String systemPrompt = config.effectiveSystemPrompt();
        new Thread(new Runnable() {
            @Override
            public void run() {
                runCompile(job, candidates, systemPrompt, userPrompt, callback);
            }
        }, "ai-compile").start();
        return job;
    }

    public static Job analyze(Config config, Store store, String input, AnalyzeCallback callback) {
        final Job job = new Job();
        final List<Providers.Candidate> candidates = Providers.candidates(
                Providers.load(store, config.apiKey), config.model);
        if (candidates.isEmpty()) {
            callback.onFailure(ERR_KEY, "");
            return job;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                runAnalyze(job, candidates, input, callback);
            }
        }, "ai-analyze").start();
        return job;
    }

    // ------------------------------------------------------- candidate walking

    private static void runCompile(Job job, List<Providers.Candidate> candidates,
                                   String systemPrompt, String userPrompt,
                                   CompileCallback callback) {
        int lastKind = ERR_HTTP;
        String lastDetail = "";
        String lastSource = "";
        for (Providers.Candidate candidate : candidates) {
            if (job.isCancelled()) {
                callback.onFailure(ERR_CANCELLED, "");
                return;
            }
            lastSource = candidate.describe();
            Attempt attempt = streamOnce(job, candidate, systemPrompt, userPrompt, callback);
            if (attempt.ok) {
                final String raw = attempt.text;
                final JSONObject usage = attempt.usage;
                final String source = candidate.describe();
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(raw, usage, source);
                    }
                });
                return;
            }
            if (job.isCancelled()) {
                callback.onFailure(ERR_CANCELLED, "");
                return;
            }
            lastKind = attempt.kind;
            lastDetail = attempt.detail;
            if (attempt.kind == ERR_OFFLINE) {
                // No candidate can reach the network, so stop instead of cycling through them.
                break;
            }
            Log.i(TAG, "switching away from " + candidate.describe() + ": " + attempt.detail);
        }
        postFailure(callback, lastKind, decorate(lastKind, lastDetail, lastSource));
    }

    private static void runAnalyze(Job job, List<Providers.Candidate> candidates, String input,
                                   AnalyzeCallback callback) {
        int lastKind = ERR_HTTP;
        String lastDetail = "";
        String lastSource = "";
        for (Providers.Candidate candidate : candidates) {
            if (job.isCancelled()) {
                callback.onFailure(ERR_CANCELLED, "");
                return;
            }
            lastSource = candidate.describe();
            Attempt attempt = requestOnce(job, candidate, SystemPrompt.INTERVIEW_ANALYSIS_PROMPT,
                    input, 0.4, 1200);
            if (attempt.ok) {
                final ClarifyResult parsed = PromptEngine.parseClarify(attempt.text);
                final String raw = attempt.text;
                final JSONObject usage = attempt.usage;
                final String source = candidate.describe();
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onReady(parsed, raw, usage, source);
                    }
                });
                return;
            }
            if (job.isCancelled()) {
                callback.onFailure(ERR_CANCELLED, "");
                return;
            }
            lastKind = attempt.kind;
            lastDetail = attempt.detail;
            if (attempt.kind == ERR_OFFLINE) {
                break;
            }
            Log.i(TAG, "analyze switching away from " + candidate.describe() + ": " + attempt.detail);
        }
        final int kind = lastKind;
        final String detail = decorate(lastKind, lastDetail, lastSource);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onFailure(kind, detail);
            }
        });
    }

    /** Says which provider was tried last, so a total failure is not a mystery. */
    private static String decorate(int kind, String detail, String source) {
        if (kind == ERR_OFFLINE || source == null || source.isEmpty()) {
            return detail == null ? "" : detail;
        }
        return detail == null || detail.isEmpty() ? source : detail + "\n(" + source + ")";
    }

    private static void postFailure(final CompileCallback callback, final int kind, final String detail) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onFailure(kind, detail);
            }
        });
    }

    // ------------------------------------------------------------- streaming

    private static final class Attempt {
        boolean ok;
        String text = "";
        JSONObject usage;
        int kind = ERR_HTTP;
        String detail = "";
    }

    private static Attempt streamOnce(Job job, Providers.Candidate candidate, String systemPrompt,
                                      String userPrompt, CompileCallback callback) {
        Attempt attempt = new Attempt();
        HttpURLConnection connection = null;
        try {
            String body = buildBody(candidate, systemPrompt, userPrompt, true, 0.7, 8192);
            connection = open(candidate, endpoint(candidate, true), body);
            job.connection = connection;

            int status = connection.getResponseCode();
            if (status != 200) {
                String errorBody = readAll(connection.getErrorStream());
                classify(attempt, status, errorBody);
                return attempt;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), UTF8));
            StringBuilder raw = new StringBuilder();
            long lastPost = 0L;
            String line;
            while ((line = reader.readLine()) != null) {
                String chunk = payloadOf(line);
                if (chunk == null) {
                    continue;
                }
                try {
                    JSONObject event = new JSONObject(chunk);
                    JSONObject usage = usageOf(candidate, event);
                    if (usage != null) {
                        attempt.usage = usage;
                    }
                    String piece = textOf(candidate, event);
                    if (piece != null) {
                        raw.append(piece);
                    }
                } catch (Throwable malformed) {
                    Log.w(TAG, "skipping unparsable stream chunk", malformed);
                }
                long now = System.currentTimeMillis();
                if (now - lastPost > 90L) {
                    lastPost = now;
                    final String snapshot = raw.toString();
                    final String source = candidate.describe();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onProgress(snapshot, source);
                        }
                    });
                }
            }
            reader.close();

            if (raw.toString().trim().isEmpty()) {
                attempt.kind = ERR_EMPTY;
                attempt.detail = "";
                return attempt;
            }
            attempt.ok = true;
            attempt.text = raw.toString();
            return attempt;
        } catch (Throwable failure) {
            if (job.isCancelled()) {
                attempt.kind = ERR_CANCELLED;
                return attempt;
            }
            attempt.kind = offlineKind(failure);
            attempt.detail = describe(failure);
            return attempt;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // --------------------------------------------------------- non-streaming

    private static Attempt requestOnce(Job job, Providers.Candidate candidate, String systemPrompt,
                                       String userPrompt, double temperature, int maxTokens) {
        Attempt attempt = new Attempt();
        HttpURLConnection connection = null;
        try {
            String body = buildBody(candidate, systemPrompt, userPrompt, false, temperature, maxTokens);
            connection = open(candidate, endpoint(candidate, false), body);
            job.connection = connection;

            int status = connection.getResponseCode();
            String responseBody = status == 200
                    ? readAll(connection.getInputStream())
                    : readAll(connection.getErrorStream());
            if (status != 200) {
                classify(attempt, status, responseBody);
                return attempt;
            }

            JSONObject root = new JSONObject(responseBody);
            String text = textOf(candidate, root);
            if (text == null || text.trim().isEmpty()) {
                attempt.kind = ERR_EMPTY;
                return attempt;
            }
            attempt.ok = true;
            attempt.text = text;
            attempt.usage = usageOf(candidate, root);
            return attempt;
        } catch (Throwable failure) {
            if (job.isCancelled()) {
                attempt.kind = ERR_CANCELLED;
                return attempt;
            }
            attempt.kind = offlineKind(failure);
            attempt.detail = describe(failure);
            return attempt;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ per-kind io

    private static String endpoint(Providers.Candidate candidate, boolean stream) {
        String base = candidate.provider.normalizedBaseUrl();
        if (ProviderProfile.KIND_OPENAI.equals(candidate.provider.kind)) {
            return base + "/chat/completions";
        }
        if (ProviderProfile.KIND_ANTHROPIC.equals(candidate.provider.kind)) {
            return base + "/messages";
        }
        return base + "/v1beta/models/" + candidate.model
                + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
    }

    private static HttpURLConnection open(Providers.Candidate candidate, String endpoint, String body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        if (ProviderProfile.KIND_OPENAI.equals(candidate.provider.kind)) {
            connection.setRequestProperty("Authorization", "Bearer " + candidate.key);
        } else if (ProviderProfile.KIND_ANTHROPIC.equals(candidate.provider.kind)) {
            connection.setRequestProperty("x-api-key", candidate.key);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            connection.setRequestProperty("x-goog-api-key", candidate.key);
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
        return connection;
    }

    private static String buildBody(Providers.Candidate candidate, String systemPrompt,
                                    String userPrompt, boolean stream,
                                    double temperature, int maxTokens) throws Exception {
        String kind = candidate.provider.kind;
        if (ProviderProfile.KIND_OPENAI.equals(kind)) {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            return new JSONObject()
                    .put("model", candidate.model)
                    .put("messages", messages)
                    .put("temperature", temperature)
                    .put("max_tokens", maxTokens)
                    .put("stream", stream)
                    .toString();
        }
        if (ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            return new JSONObject()
                    .put("model", candidate.model)
                    .put("system", systemPrompt)
                    .put("messages", messages)
                    .put("max_tokens", maxTokens)
                    .put("temperature", temperature)
                    .put("stream", stream)
                    .toString();
        }

        JSONObject systemInstruction = new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userPrompt)));
        return new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(userContent))
                .put("generationConfig", new JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxTokens))
                .toString();
    }

    /** Returns the JSON payload of an SSE data line, or null for anything else. */
    private static String payloadOf(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        String chunk = line.substring(5).trim();
        if (chunk.isEmpty() || "[DONE]".equals(chunk)) {
            return null;
        }
        return chunk;
    }

    /** Pulls the text piece out of one provider event, whichever shape it has. */
    private static String textOf(Providers.Candidate candidate, JSONObject event) {
        String kind = candidate.provider.kind;
        try {
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                JSONArray choices = event.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    return null;
                }
                JSONObject first = choices.optJSONObject(0);
                JSONObject delta = first.optJSONObject("delta");
                if (delta != null && delta.has("content")) {
                    return delta.optString("content", "");
                }
                JSONObject message = first.optJSONObject("message");
                if (message != null && message.has("content")) {
                    return message.optString("content", "");
                }
                return null;
            }
            if (ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
                String type = event.optString("type", "");
                if ("content_block_delta".equals(type)) {
                    JSONObject delta = event.optJSONObject("delta");
                    return delta == null ? null : delta.optString("text", "");
                }
                if (event.has("content")) {
                    StringBuilder text = new StringBuilder();
                    JSONArray blocks = event.optJSONArray("content");
                    if (blocks != null) {
                        for (int index = 0; index < blocks.length(); index++) {
                            JSONObject block = blocks.optJSONObject(index);
                            if (block != null) {
                                text.append(block.optString("text", ""));
                            }
                        }
                    }
                    return text.length() == 0 ? null : text.toString();
                }
                return null;
            }
            JSONArray candidates = event.optJSONArray("candidates");
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
            return text.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Normalizes every provider's usage block onto the Gemini field names UsageStats expects. */
    private static JSONObject usageOf(Providers.Candidate candidate, JSONObject event) {
        String kind = candidate.provider.kind;
        try {
            if (ProviderProfile.KIND_GEMINI.equals(kind)) {
                return event.optJSONObject("usageMetadata");
            }
            JSONObject usage = event.optJSONObject("usage");
            if (usage == null && ProviderProfile.KIND_ANTHROPIC.equals(kind)) {
                JSONObject message = event.optJSONObject("message");
                usage = message == null ? null : message.optJSONObject("usage");
            }
            if (usage == null) {
                return null;
            }
            int input = usage.optInt("prompt_tokens",
                    usage.optInt("input_tokens", usage.optInt("promptTokenCount", 0)));
            int output = usage.optInt("completion_tokens",
                    usage.optInt("output_tokens", usage.optInt("candidatesTokenCount", 0)));
            JSONObject normalized = new JSONObject();
            normalized.put("promptTokenCount", input);
            normalized.put("candidatesTokenCount", output);
            normalized.put("totalTokenCount", usage.optInt("total_tokens", input + output));
            return normalized;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------- error map

    private static void classify(Attempt attempt, int status, String errorBody) {
        String detail = extractMessage(errorBody);
        if (looksLikeHtml(errorBody)) {
            attempt.kind = ERR_BLOCKED;
            attempt.detail = "";
            return;
        }
        attempt.detail = detail;
        if (status == 401 || status == 403) {
            attempt.kind = ERR_KEY;
            return;
        }
        if (status == 400 && detail.toLowerCase().contains("api key")) {
            attempt.kind = ERR_KEY;
            return;
        }
        if (status == 404) {
            attempt.kind = ERR_MODEL;
            return;
        }
        if (status == 429) {
            attempt.kind = ERR_RATE_LIMIT;
            return;
        }
        if (status == 402) {
            // Out of credit: another key or provider may still work.
            attempt.kind = ERR_RATE_LIMIT;
            return;
        }
        attempt.kind = ERR_HTTP;
        attempt.detail = "[" + status + "] " + detail;
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
            String message = root.optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
        } catch (Throwable ignored) {
        }
        String trimmed = errorBody.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
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

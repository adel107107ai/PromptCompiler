package com.promptengine.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Fetches the model list of a provider so a key can be checked before it is trusted. */
final class ProviderProbe {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private ProviderProbe() {
    }

    /** Returns a human-readable report; never throws. */
    static String listModels(String kind, String base, String key) {
        HttpURLConnection connection = null;
        try {
            String endpoint;
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                endpoint = base + "/models";
            } else {
                endpoint = base + "/v1beta/models?pageSize=200";
            }
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
            String body = status == 200
                    ? readAll(connection.getInputStream())
                    : readAll(connection.getErrorStream());
            if (status != 200) {
                return "HTTP " + status + "\n" + trim(body);
            }

            JSONArray entries;
            if (ProviderProfile.KIND_OPENAI.equals(kind)) {
                entries = new JSONObject(body).optJSONArray("data");
            } else {
                entries = new JSONObject(body).optJSONArray("models");
            }
            if (entries == null) {
                return "No model list in the response.\n" + trim(body);
            }
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (int index = 0; index < entries.length() && shown < 40; index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String id = entry.optString("id", entry.optString("name", ""));
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (id.isEmpty()) {
                    continue;
                }
                out.append("• ").append(id).append('\n');
                shown++;
            }
            out.append('\n').append(shown).append(" / ").append(entries.length());
            return out.toString();
        } catch (Throwable failure) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
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

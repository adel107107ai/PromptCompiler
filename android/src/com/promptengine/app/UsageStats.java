package com.promptengine.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/** Port of prompt_compiler/services/usage.py compute_usage(). */
public final class UsageStats {

    private UsageStats() {
    }

    public static long localMidnight() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static JSONObject compute(Store store, List<ModelInfo> models) {
        JSONObject out = new JSONObject();
        try {
            JSONArray events = store.loadUsage();
            long now = System.currentTimeMillis();
            long dayStart = localMidnight();
            long minuteStart = now - 60000L;

            int requests = 0;
            int input = 0;
            int output = 0;
            int thoughts = 0;
            int total = 0;

            JSONArray todayEvents = new JSONArray();
            JSONArray minuteEvents = new JSONArray();
            for (int index = 0; index < events.length(); index++) {
                JSONObject event = events.optJSONObject(index);
                if (event == null || event.optLong("t", 0) < dayStart) {
                    continue;
                }
                todayEvents.put(event);
                if (event.optLong("t", 0) >= minuteStart) {
                    minuteEvents.put(event);
                }
                requests++;
                input += event.optInt("in", 0);
                output += event.optInt("out", 0);
                thoughts += event.optInt("th", 0);
                total += event.optInt("tot", 0);
            }

            JSONObject today = new JSONObject();
            today.put("requests", requests);
            today.put("input", input);
            today.put("output", output);
            today.put("thoughts", thoughts);
            today.put("total", total);
            out.put("today", today);

            JSONArray modelStats = new JSONArray();
            for (ModelInfo model : models) {
                int modelRequests = 0;
                int modelTokens = 0;
                for (int index = 0; index < todayEvents.length(); index++) {
                    JSONObject event = todayEvents.optJSONObject(index);
                    if (event != null && model.id.equals(event.optString("m"))) {
                        modelRequests++;
                        modelTokens += event.optInt("tot", 0);
                    }
                }
                int minuteRequests = 0;
                int minuteTokens = 0;
                for (int index = 0; index < minuteEvents.length(); index++) {
                    JSONObject event = minuteEvents.optJSONObject(index);
                    if (event != null && model.id.equals(event.optString("m"))) {
                        minuteRequests++;
                        minuteTokens += event.optInt("tot", 0);
                    }
                }
                JSONObject stat = new JSONObject();
                stat.put("id", model.id);
                stat.put("name", model.name);
                stat.put("rpm", model.rpm == null ? JSONObject.NULL : model.rpm);
                stat.put("tpm", model.tpm == null ? JSONObject.NULL : model.tpm);
                stat.put("rpd", model.rpd == null ? JSONObject.NULL : model.rpd);
                stat.put("today_requests", modelRequests);
                stat.put("today_tokens", modelTokens);
                stat.put("minute_requests", minuteRequests);
                stat.put("minute_tokens", minuteTokens);
                modelStats.put(stat);
            }
            out.put("models", modelStats);
        } catch (Throwable ignored) {
        }
        return out;
    }
}

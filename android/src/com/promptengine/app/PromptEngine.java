package com.promptengine.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Java port of prompt_compiler/prompt_engine.py.
 *
 * SYSTEM_PROMPT is not repeated here: SystemPrompt.java is generated from the Python source by
 * tools/gen_java_prompt.py, so both platforms share one prompt policy.
 */
public final class PromptEngine {

    public static final String SYSTEM_PROMPT = SystemPrompt.TEXT;

    /** What parse() returns when a response contains no usable English prompt. */
    public static final String EMPTY_PROMPT = "No prompt was generated.";

    private static final List<String> CATEGORIES =
            Arrays.asList(SystemPrompt.KNOWN_CATEGORIES);

    private PromptEngine() {
    }

    /** The instruction wrapper the Python service sends alongside the user's request. */
    public static String buildUserPrompt(String persianInput) {
        return "Compile the following Persian request into an optimized English prompt. "
                + "The English prompt is the canonical result. Then translate that exact finalized "
                + "English prompt into a faithful Persian version. Do not generate Persian "
                + "independently from the original input. Follow your instructions:\n\n"
                + persianInput;
    }

    public static PromptResult parse(String rawText) {
        String cleaned = rawText == null ? "" : rawText.trim();
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.replaceFirst("(?is)^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("(?s)\\s*```$", "").trim();
        }

        JSONObject data = null;
        try {
            data = new JSONObject(cleaned);
        } catch (Throwable ignored) {
            data = null;
        }

        PromptResult result = new PromptResult();
        if (data != null) {
            String category = asString(data.opt("detected_category"));
            result.detectedCategory = CATEGORIES.contains(category) ? category : "general";
            result.detectedSubcategory = asString(data.opt("detected_subcategory"));
            result.detectedPurpose = asString(data.opt("detected_purpose"));
            result.englishPrompt = orElse(
                    asString(data.opt("english_prompt")), asString(data.opt("englishPrompt")));
            result.persianPrompt = orElse(
                    asString(data.opt("persian_prompt")), asString(data.opt("persianPrompt")));
            result.preservedRequirements = asList(data.opt("preserved_requirements"));
            result.assumptions = asList(data.opt("assumptions"));
            result.missingInformation = asList(data.opt("missing_information"));
            result.suggestions = asList(data.opt("suggestions"));
        } else {
            result.englishPrompt = cleaned;
        }

        if (result.englishPrompt.isEmpty()) {
            result.englishPrompt = EMPTY_PROMPT;
        }
        return result;
    }

    /**
     * Extracts a string field from a still-arriving JSON document, so the UI can show the
     * prompt being written instead of a blank screen. Returns null while the field is not
     * yet present; incomplete escapes simply yield a shorter prefix that grows on the next call.
     */
    public static String partialField(String buffer, String field) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        String key = "\"" + field + "\"";
        int keyAt = buffer.indexOf(key);
        if (keyAt < 0) {
            return null;
        }
        int colon = buffer.indexOf(':', keyAt + key.length());
        if (colon < 0) {
            return null;
        }
        int quote = buffer.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = quote + 1;
        while (i < buffer.length()) {
            char ch = buffer.charAt(i);
            if (ch == '\\') {
                if (i + 1 >= buffer.length()) {
                    break;
                }
                char next = buffer.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                    i += 2;
                } else if (next == 'r') {
                    out.append('\r');
                    i += 2;
                } else if (next == 't') {
                    out.append('\t');
                    i += 2;
                } else if (next == 'u') {
                    if (i + 5 >= buffer.length()) {
                        break;
                    }
                    try {
                        out.append((char) Integer.parseInt(buffer.substring(i + 2, i + 6), 16));
                        i += 6;
                    } catch (NumberFormatException bad) {
                        i += 2;
                    }
                } else {
                    out.append(next);
                    i += 2;
                }
            } else if (ch == '"') {
                break;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static String orElse(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private static String asString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "";
    }

    private static List<String> asList(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                String item = asString(array.opt(index));
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        } else if (value instanceof String) {
            String single = ((String) value).trim();
            if (!single.isEmpty()) {
                items.add(single);
            }
        }
        return items;
    }
}

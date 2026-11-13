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

    /** Upper bound on options per interview question, mirroring prompt_engine.py. */
    public static final int MAX_QUESTION_OPTIONS = 10;

    private static final List<String> CATEGORIES =
            Arrays.asList(SystemPrompt.KNOWN_CATEGORIES);

    private PromptEngine() {
    }

    /** The instruction wrapper the Python service sends alongside the user's request. */
    public static String buildUserPrompt(String persianInput) {
        return buildUserPrompt(persianInput, null);
    }

    /** The compile-phase user message: the request, plus any answers from the discovery phase. */
    public static String buildUserPrompt(String persianInput, List<String[]> answers) {
        StringBuilder prompt = new StringBuilder(SystemPrompt.REQUEST_PREFIX).append(persianInput);
        String block = buildAnswersBlock(answers);
        if (!block.isEmpty()) {
            prompt.append("\n\n").append(block);
        }
        return prompt.toString();
    }

    /**
     * Renders the collected answers as explicit requirements. Mirrors build_answers_block() in
     * prompt_compiler/prompt_engine.py, including the "- question: answer" line format.
     */
    public static String buildAnswersBlock(List<String[]> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        for (String[] pair : answers) {
            if (pair == null) {
                continue;
            }
            String answer = clean(pair.length > 1 ? pair[1] : "");
            if (answer.isEmpty()) {
                continue;
            }
            String question = clean(pair.length > 0 ? pair[0] : "");
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append(question.isEmpty() ? "- " + answer : "- " + question + ": " + answer);
        }
        if (lines.length() == 0) {
            return "";
        }
        return SystemPrompt.CLARIFIED_SPECIFICATIONS_HEADER + "\n" + lines;
    }

    /** Port of parse_clarify_output(). Unreadable output degrades to the fast path. */
    public static ClarifyResult parseClarify(String rawText) {
        String cleaned = rawText == null ? "" : rawText.trim();
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.replaceFirst("(?is)^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("(?s)\\s*```$", "").trim();
        }

        JSONObject data = tryParseObject(cleaned);
        if (data == null) {
            String span = braceSpan(cleaned);
            if (span != null) {
                data = tryParseObject(span);
            }
        }

        ClarifyResult result = new ClarifyResult();
        if (data == null) {
            // A truncated or malformed answer may still carry whole question objects plus an
            // explicit gate. Recovering them keeps a real ambiguity from being ignored, which is
            // what used to make the app jump straight to compiling with no interview.
            JSONArray salvaged = salvageQuestionObjects(cleaned);
            if (salvaged.length() > 0 && gateIsOpen(cleaned)) {
                data = new JSONObject();
                try {
                    data.put("has_critical_ambiguity", true);
                    data.put("questions", salvaged);
                } catch (Throwable ignored) {
                    data = null;
                }
            }
        }
        if (data == null) {
            result.degraded = true;
            return result;
        }

        result.hasCriticalAmbiguity = data.optBoolean("has_critical_ambiguity", false);
        String category = asString(data.opt("detected_category"));
        result.detectedCategory = CATEGORIES.contains(category) ? category : "general";
        result.reasoningFa = asString(data.opt("reasoning_fa"));

        JSONArray questions = data.optJSONArray("questions");
        if (questions != null) {
            for (int index = 0;
                 index < questions.length() && result.questions.size() < SystemPrompt.MAX_CLARIFY_QUESTIONS;
                 index++) {
                JSONObject item = questions.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String text = asString(item.opt("question_fa"));
                if (text.isEmpty()) {
                    text = asString(item.opt("question"));
                }
                if (text.isEmpty()) {
                    continue;
                }
                ClarifyResult.Question question = new ClarifyResult.Question();
                question.question = text;
                String id = asString(item.opt("id"));
                question.id = id.isEmpty() ? "q" + (result.questions.size() + 1) : id;
                question.why = asString(item.opt("why_needed_fa"));
                if (question.why.isEmpty()) {
                    question.why = asString(item.opt("why"));
                }
                List<String> options = asList(item.opt("options_fa"));
                if (options.isEmpty()) {
                    options = asList(item.opt("options"));
                }
                options = dedupe(options);
                options = singleDeferral(options);
                for (int opt = 0; opt < options.size() && question.options.size() < MAX_QUESTION_OPTIONS; opt++) {
                    question.options.add(options.get(opt));
                }
                question.multi = item.optBoolean("multi", true);
                // The recommendation carries the novice user; empty falls back to the first
                // option so the UI can always highlight one.
                String recommended = asString(item.opt("recommended_fa"));
                if (recommended.isEmpty() && !question.options.isEmpty()) {
                    recommended = question.options.get(0);
                }
                question.recommended = recommended;
                result.questions.add(question);
            }
        }

        // An interview that claims to be needed but has no usable question cannot run.
        if (!result.hasCriticalAmbiguity || result.questions.isEmpty()) {
            result.hasCriticalAmbiguity = false;
            result.questions.clear();
        }
        return result;
    }

    /** True for any wording of the safe-deferral option, so duplicates can be removed. */
    public static boolean isDeferral(String option) {
        String text = clean(option);
        return text.startsWith("نمی\u200cدانم") || text.startsWith("نمی دانم")
                || text.startsWith("نمی\u200cدانم");
    }

    /** Drops options that repeat an earlier one, ignoring spacing and ZWNJ differences. */
    private static List<String> dedupe(List<String> options) {
        List<String> keys = new ArrayList<>();
        List<String> unique = new ArrayList<>();
        for (String option : options) {
            String key = clean(option).replace("\u200c", "").replace("\u200f", "")
                    .replace(" ", "");
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
                unique.add(option);
            }
        }
        return unique;
    }

    /** Keeps at most one deferral, at the end, where the UI expects it. */
    private static List<String> singleDeferral(List<String> options) {
        String deferral = null;
        List<String> others = new ArrayList<>();
        for (String option : options) {
            if (isDeferral(option)) {
                if (deferral == null) {
                    deferral = option;
                }
            } else {
                others.add(option);
            }
        }
        if (deferral != null) {
            others.add(deferral);
        }
        return others;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** The outermost {...} of a response that has prose or fences around the JSON. */
    private static String braceSpan(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start + 1) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** True when the response clearly states the gate is open, even if the JSON is broken. */
    private static boolean gateIsOpen(String text) {
        return java.util.regex.Pattern
                .compile("\\\"has_critical_ambiguity\\\"\\s*:\\s*true")
                .matcher(text)
                .find();
    }

    /** Pulls every complete question object out of a malformed or truncated response. */
    private static JSONArray salvageQuestionObjects(String text) {
        JSONArray salvaged = new JSONArray();
        int marker = text.indexOf("\"questions\"");
        int start = marker < 0 ? -1 : text.indexOf('[', marker);
        if (start < 0) {
            return salvaged;
        }
        int cursor = start + 1;
        while (cursor < text.length() && salvaged.length() < SystemPrompt.MAX_CLARIFY_QUESTIONS) {
            int opening = text.indexOf('{', cursor);
            if (opening < 0) {
                break;
            }
            int depth = 0;
            int closing = -1;
            for (int index = opening; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        closing = index;
                        break;
                    }
                }
            }
            if (closing < 0) {
                break;
            }
            JSONObject item = tryParseObject(text.substring(opening, closing + 1));
            if (item != null) {
                salvaged.put(item);
            }
            cursor = closing + 1;
        }
        return salvaged;
    }

    public static PromptResult parse(String rawText) {
        String cleaned = rawText == null ? "" : rawText.trim();
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.replaceFirst("(?is)^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("(?s)\\s*```$", "").trim();
        }

        // Layer 1: the whole text as JSON.
        JSONObject data = tryParseObject(cleaned);
        // Layer 2: commentary or fences around the JSON - parse the brace span only.
        if (data == null) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start + 1) {
                data = tryParseObject(cleaned.substring(start, end + 1));
            }
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
            // Layer 3: the response is not a readable JSON object. Extract the two prompts
            // straight out of the raw text before falling back - dumping everything into the
            // English box is what merged the Persian and English prompts into one field.
            String english = partialField(cleaned, "english_prompt");
            String persian = partialField(cleaned, "persian_prompt");
            if (english != null && !english.trim().isEmpty()) {
                result.englishPrompt = english.trim();
                if (persian != null && !persian.trim().isEmpty()) {
                    result.persianPrompt = persian.trim();
                }
            } else {
                result.englishPrompt = cleaned;
            }
        }

        if (result.englishPrompt.isEmpty()) {
            result.englishPrompt = EMPTY_PROMPT;
        }
        return result;
    }

    private static JSONObject tryParseObject(String text) {
        try {
            return new JSONObject(text);
        } catch (Throwable ignored) {
            return null;
        }
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

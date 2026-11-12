package com.promptengine.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The provider registry plus the candidate order used for automatic switching.
 *
 * Candidates are tried in order: for every provider, each key is tried with each model, so a
 * rejected key or an exhausted model moves on to the next key or model automatically.
 */
public final class Providers {

    /** Separates provider id and model id in a stored selection, e.g. "gemini|gemini-2.5-flash". */
    public static final String SEPARATOR = "|";

    private Providers() {
    }

    // ------------------------------------------------------------- registry

    public static List<ProviderProfile> load(Store store, String legacyApiKey) {
        List<ProviderProfile> providers = parse(store.loadProvidersJson());
        if (providers.isEmpty()) {
            ProviderProfile gemini = defaultGemini(ModelCatalog.DEFAULT_MODEL);
            if (legacyApiKey != null && !legacyApiKey.trim().isEmpty()) {
                // Migration: the key stored before providers existed becomes the first key.
                gemini.apiKeys.add(legacyApiKey.trim());
            }
            providers.add(gemini);
            store.saveProvidersJson(dump(providers));
        }
        return providers;
    }

    public static List<ProviderProfile> load(Store store) {
        return load(store, "");
    }

    public static void save(Store store, List<ProviderProfile> providers) {
        store.saveProvidersJson(dump(providers));
    }

    /** Migration path: the legacy single key becomes the first key of the Gemini provider. */
    public static ProviderProfile defaultGemini(String model) {
        ProviderProfile provider = new ProviderProfile(
                "gemini", "Google Gemini", ProviderProfile.KIND_GEMINI,
                ModelCatalog.DEFAULT_BASE_URL);
        for (ModelInfo info : ModelCatalog.BUILT_IN) {
            if (info.generate) {
                provider.models.add(info.id);
            }
        }
        if (provider.models.isEmpty()) {
            provider.models.add(model);
        }
        return provider;
    }

    public static ProviderProfile byId(List<ProviderProfile> providers, String id) {
        for (ProviderProfile provider : providers) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        return null;
    }

    public static ProviderProfile primary(List<ProviderProfile> providers) {
        for (ProviderProfile provider : providers) {
            if (provider.usable()) {
                return provider;
            }
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    public static boolean anyUsable(List<ProviderProfile> providers) {
        for (ProviderProfile provider : providers) {
            if (provider.usable()) {
                return true;
            }
        }
        return false;
    }

    public static String newId() {
        return "p" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------- selection

    /** "providerId|modelId" for the selected model, with a safe fallback. */
    public static String encode(String providerId, String modelId) {
        return providerId + SEPARATOR + modelId;
    }

    public static String providerIdOf(String selection) {
        int cut = selection == null ? -1 : selection.indexOf(SEPARATOR);
        return cut > 0 ? selection.substring(0, cut) : "";
    }

    public static String modelIdOf(String selection, String fallback) {
        int cut = selection == null ? -1 : selection.indexOf(SEPARATOR);
        if (cut <= 0 || cut + 1 >= (selection == null ? 0 : selection.length())) {
            return fallback;
        }
        return selection.substring(cut + 1);
    }

    /** Every provider|model pair the user can pick, in provider order. */
    public static List<ModelChoice> choices(List<ProviderProfile> providers) {
        List<ModelChoice> choices = new ArrayList<>();
        for (ProviderProfile provider : providers) {
            if (!provider.enabled) {
                continue;
            }
            for (String model : provider.models) {
                choices.add(new ModelChoice(provider, model));
            }
        }
        return choices;
    }

    public static final class ModelChoice {
        public final ProviderProfile provider;
        public final String model;

        ModelChoice(ProviderProfile provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        public String selection() {
            return encode(provider.id, model);
        }

        public String label() {
            return ModelCatalog.nameFor(model) + " — " + provider.name;
        }
    }

    // ------------------------------------------------------------ candidates

    /** One attempt: a provider, one of its keys, one of its models. */
    public static final class Candidate {
        public final ProviderProfile provider;
        public final String key;
        public final String model;

        Candidate(ProviderProfile provider, String key, String model) {
            this.provider = provider;
            this.key = key;
            this.model = model;
        }

        public String describe() {
            return provider.name + " / " + model;
        }
    }

    /**
     * The order in which attempts are made. The selected model is tried first, then the rest of
     * its provider, then every other enabled provider - each with every key it holds.
     */
    public static List<Candidate> candidates(List<ProviderProfile> providers, String selection) {
        List<Candidate> candidates = new ArrayList<>();
        String preferredProvider = providerIdOf(selection);
        String preferredModel = modelIdOf(selection, "");

        ProviderProfile first = byId(providers, preferredProvider);
        if (first != null) {
            addProvider(candidates, first, preferredModel);
        }
        for (ProviderProfile provider : providers) {
            if (first != null && provider.id.equals(first.id)) {
                continue;
            }
            addProvider(candidates, provider, "");
        }
        // A provider without keys cannot answer at all, so it is never attempted.
        List<Candidate> usable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.key != null && !candidate.key.trim().isEmpty()) {
                usable.add(candidate);
            }
        }
        return usable;
    }

    private static void addProvider(List<Candidate> out, ProviderProfile provider, String preferredModel) {
        if (provider == null || !provider.enabled) {
            return;
        }
        List<String> models = new ArrayList<>();
        if (preferredModel != null && !preferredModel.isEmpty() && provider.models.contains(preferredModel)) {
            models.add(preferredModel);
        }
        for (String model : provider.models) {
            if (!models.contains(model)) {
                models.add(model);
            }
        }
        for (String key : provider.apiKeys) {
            for (String model : models) {
                out.add(new Candidate(provider, key, model));
            }
        }
    }

    // ------------------------------------------------------------------ json

    public static String dump(List<ProviderProfile> providers) {
        JSONArray array = new JSONArray();
        try {
            for (ProviderProfile provider : providers) {
                JSONObject item = new JSONObject();
                item.put("id", provider.id);
                item.put("name", provider.name);
                item.put("kind", provider.kind);
                item.put("baseUrl", provider.baseUrl);
                item.put("enabled", provider.enabled);
                item.put("lastProbeAt", provider.lastProbeAt);
                item.put("lastProbeReady", provider.lastProbeReady);
                item.put("lastProbeTotal", provider.lastProbeTotal);
                item.put("apiKeys", new JSONArray(provider.apiKeys));
                item.put("models", new JSONArray(provider.models));
                array.put(item);
            }
        } catch (Throwable ignored) {
        }
        return array.toString();
    }

    public static List<ProviderProfile> parse(String json) {
        List<ProviderProfile> providers = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return providers;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                ProviderProfile provider = new ProviderProfile(
                        id,
                        item.optString("name", id),
                        item.optString("kind", ProviderProfile.KIND_GEMINI),
                        item.optString("baseUrl", ""));
                provider.enabled = item.optBoolean("enabled", true);
                provider.lastProbeAt = item.optLong("lastProbeAt", 0L);
                provider.lastProbeReady = item.optInt("lastProbeReady", 0);
                provider.lastProbeTotal = item.optInt("lastProbeTotal", 0);
                JSONArray keys = item.optJSONArray("apiKeys");
                if (keys != null) {
                    for (int k = 0; k < keys.length(); k++) {
                        String key = keys.optString(k, "").trim();
                        if (!key.isEmpty()) {
                            provider.apiKeys.add(key);
                        }
                    }
                }
                JSONArray models = item.optJSONArray("models");
                if (models != null) {
                    for (int m = 0; m < models.length(); m++) {
                        String model = models.optString(m, "").trim();
                        if (!model.isEmpty()) {
                            provider.models.add(model);
                        }
                    }
                }
                providers.add(provider);
            }
        } catch (Throwable ignored) {
        }
        return providers;
    }
}

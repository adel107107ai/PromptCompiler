package com.promptengine.app;

import java.util.ArrayList;
import java.util.List;

/** Normalized model response, mirroring the schema of parse_model_output(). */
public final class PromptResult {

    public String detectedCategory = "general";
    public String detectedSubcategory = "";
    public String detectedPurpose = "";
    public String englishPrompt = "";
    public String persianPrompt = "";
    public List<String> preservedRequirements = new ArrayList<>();
    public List<String> assumptions = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();
    public List<String> suggestions = new ArrayList<>();
}

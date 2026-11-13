package com.promptengine.app;

import java.util.ArrayList;
import java.util.List;

/**
 * What the discovery phase worked out before anything is written.
 *
 * A request that is already clear comes back with {@code ready} true and no questions, so the
 * compile phase runs immediately instead of forcing an interview.
 */
public final class ClarifyResult {

    /** Whether the critical-ambiguity gate opened the interview. */
    public boolean hasCriticalAmbiguity = false;
    public String detectedCategory = "general";
    public String reasoningFa = "";
    public List<Question> questions = new ArrayList<>();
    /** True when the model's answer could not be read at all; the user is never blocked by it. */
    public boolean degraded = false;

    public static final class Question {
        public String id = "";
        public String question = "";
        public String why = "";
        /** The typical best answer for this request; a novice can just accept it. */
        public String recommended = "";
        /** True when several options can be true at once, so the UI offers toggles. */
        public boolean multi = true;
        public final List<String> options = new ArrayList<>();
    }
}

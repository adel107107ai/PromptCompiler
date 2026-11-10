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

    public String understanding = "";
    public List<String> ambiguities = new ArrayList<>();
    public List<Question> questions = new ArrayList<>();
    public boolean ready = true;
    /** True when the model's answer could not be read at all; the user is never blocked by it. */
    public boolean degraded = false;

    public static final class Question {
        public String id = "";
        public String question = "";
        public String why = "";
        public final List<String> options = new ArrayList<>();
    }
}

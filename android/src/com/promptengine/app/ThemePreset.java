package com.promptengine.app;

import android.app.Activity;

/**
 * The three looks the user can choose between.
 *
 * Each look is a whole theme (palette + status bar + dialog surface), applied before a screen
 * inflates its layout, so every drawable and layout follows the choice without knowing about it.
 */
public final class ThemePreset {

    public static final String MINIMAL = "a";
    public static final String DARK = "b";
    public static final String WARM = "c";
    public static final String DEFAULT = DARK;

    private ThemePreset() {
    }

    public static String normalize(String look) {
        if (MINIMAL.equals(look) || WARM.equals(look) || DARK.equals(look)) {
            return look;
        }
        return DEFAULT;
    }

    public static int styleFor(String look) {
        String normalized = normalize(look);
        if (MINIMAL.equals(normalized)) {
            return R.style.Theme_A;
        }
        if (WARM.equals(normalized)) {
            return R.style.Theme_C;
        }
        return R.style.Theme_B;
    }

    /** Applies the stored look. Returns it so a screen can notice later changes. */
    public static String apply(Activity activity) {
        String look = normalize(new Store(activity).loadLook());
        activity.setTheme(styleFor(look));
        return look;
    }

    public static int nameFor(String look) {
        String normalized = normalize(look);
        if (MINIMAL.equals(normalized)) {
            return R.string.look_a;
        }
        if (WARM.equals(normalized)) {
            return R.string.look_c;
        }
        return R.string.look_b;
    }

    public static int descriptionFor(String look) {
        String normalized = normalize(look);
        if (MINIMAL.equals(normalized)) {
            return R.string.look_a_desc;
        }
        if (WARM.equals(normalized)) {
            return R.string.look_c_desc;
        }
        return R.string.look_b_desc;
    }

    public static String[] all() {
        return new String[]{MINIMAL, DARK, WARM};
    }
}

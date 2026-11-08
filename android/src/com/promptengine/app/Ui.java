package com.promptengine.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Small view helpers shared by the screens (no AndroidX, so everything is built by hand). */
final class Ui {

    private static final Locale FA = Locale.forLanguageTag("fa-IR");

    private Ui() {
    }

    static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    /** Puts text on the clipboard. Returns false when there was nothing to copy. */
    static boolean copy(Context context, String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Prompt Engine", text));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String formatInt(long value) {
        try {
            return NumberFormat.getInstance(FA).format(value);
        } catch (Throwable ignored) {
            return String.valueOf(value);
        }
    }

    static String formatTokens(long value) {
        if (value >= 1000000L) {
            return trim(value / 1000000.0) + "M";
        }
        if (value >= 1000L) {
            return trim(value / 1000.0) + "K";
        }
        return String.valueOf(value);
    }

    private static String trim(double value) {
        String text = String.format(Locale.US, "%.2f", value);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(matchWrap());
        return column;
    }

    static TextView label(Context context, String text, int colorRes, float sizeSp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(context.getColor(colorRes));
        view.setTextSize(sizeSp);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        return view;
    }

    /** One bullet of an analysis list. */
    static TextView bullet(Context context, String text) {
        TextView view = label(context, "• " + text, R.color.text, 13f);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setPadding(0, dp(context, 3), 0, 0);
        return view;
    }

    static void fillBullets(LinearLayout container, Context context, List<String> items) {
        container.removeAllViews();
        for (String item : items) {
            container.addView(bullet(context, item));
        }
    }

    /** Label + value + a meter bar for a quota row. */
    static View meterRow(Context context, String title, long used, Integer limit) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(context, 12);
        row.setLayoutParams(params);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutParams(matchWrap());

        TextView name = label(context, title, R.color.muted, 12f);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);

        String valueText = limit == null
                ? context.getString(R.string.usage_no_limit)
                : formatInt(used) + " / " + formatInt(limit);
        TextView value = label(context, valueText, R.color.text, 12f);
        head.addView(value);
        row.addView(head);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_meter);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6));
        barParams.topMargin = dp(context, 6);
        bar.setLayoutParams(barParams);

        if (limit != null && limit > 0) {
            float ratio = Math.min(1f, (float) used / (float) limit);
            View filled = new View(context);
            filled.setBackgroundResource(R.drawable.bg_meter_fill);
            bar.addView(filled, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(ratio, 0.0001f)));
            View rest = new View(context);
            bar.addView(rest, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1f - ratio, 0.0001f)));
        }
        row.addView(bar);
        return row;
    }
}

package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The single screen: Persian input, compiled English prompt, analysis, copy and share. */
public class MainActivity extends Activity {

    private Store store;
    private Config config;
    private GeminiClient.Job job;
    private String submittedInput = "";

    private EditText input;
    private Button compileButton;
    private Button modelButton;
    private ProgressBar progress;
    private TextView status;
    private View resultCard;
    private View errorCard;
    private TextView errorText;
    private TextView badgeCategory;
    private TextView badgeSubcategory;
    private TextView purpose;
    private TextView outputEnglish;
    private TextView outputPersian;
    private LinearLayout blockRequirements;
    private LinearLayout blockAssumptions;
    private LinearLayout blockMissing;
    private LinearLayout blockSuggestions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new Store(this);
        config = store.loadConfig();

        input = findViewById(R.id.input);
        compileButton = findViewById(R.id.btn_compile);
        modelButton = findViewById(R.id.btn_model);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        resultCard = findViewById(R.id.result_card);
        errorCard = findViewById(R.id.error_card);
        errorText = findViewById(R.id.error_text);
        badgeCategory = findViewById(R.id.badge_category);
        badgeSubcategory = findViewById(R.id.badge_subcategory);
        purpose = findViewById(R.id.purpose);
        outputEnglish = findViewById(R.id.out_english);
        outputPersian = findViewById(R.id.out_persian);
        blockRequirements = findViewById(R.id.block_requirements);
        blockAssumptions = findViewById(R.id.block_assumptions);
        blockMissing = findViewById(R.id.block_missing);
        blockSuggestions = findViewById(R.id.block_suggestions);

        ((TextView) blockRequirements.findViewById(R.id.block_title)).setText(R.string.label_requirements);
        ((TextView) blockAssumptions.findViewById(R.id.block_title)).setText(R.string.label_assumptions);
        ((TextView) blockMissing.findViewById(R.id.block_title)).setText(R.string.label_missing);
        ((TextView) blockSuggestions.findViewById(R.id.block_title)).setText(R.string.label_suggestions);

        compileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startCompile();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                input.setText("");
                resultCard.setVisibility(View.GONE);
                hideError();
            }
        });
        modelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showModelPicker();
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu();
            }
        });
        findViewById(R.id.btn_copy_english).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyToClipboard(outputEnglish.getText().toString());
            }
        });
        findViewById(R.id.btn_copy_persian).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyToClipboard(outputPersian.getText().toString());
            }
        });
        findViewById(R.id.btn_share_english).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareText(outputEnglish.getText().toString());
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = store.loadConfig();
        modelButton.setText(ModelCatalog.nameFor(config.model));
    }

    @Override
    protected void onDestroy() {
        if (job != null) {
            job.cancel();
            job = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ share in

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.trim().isEmpty()) {
                input.setText(shared.trim());
                input.setSelection(input.getText().length());
                startCompile();
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (selected != null && selected.toString().trim().length() > 0) {
                input.setText(selected.toString().trim());
                input.setSelection(input.getText().length());
                startCompile();
            }
        }
    }

    // ------------------------------------------------------------ compiling

    private void startCompile() {
        if (job != null) {
            job.cancel();
            job = null;
            resetCompileButton();
            progress.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            Ui.toast(this, getString(R.string.cancelled));
            return;
        }

        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Ui.toast(this, getString(R.string.err_empty_prompt));
            return;
        }
        config = store.loadConfig();
        if (config.apiKey.trim().isEmpty()) {
            Ui.toast(this, getString(R.string.err_need_key));
            openSettings();
            return;
        }
        if (!isOnline()) {
            Ui.toast(this, getString(R.string.err_offline));
            return;
        }

        submittedInput = text;
        hideError();
        resultCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.status_connecting);
        compileButton.setText(R.string.btn_cancel);

        final String model = config.model;
        job = GeminiClient.compile(config, text, new GeminiClient.CompileCallback() {
            @Override
            public void onProgress(String rawSoFar) {
                status.setText(getString(R.string.status_streaming)
                        + " · " + Ui.formatInt(rawSoFar.length()));
                String english = PromptEngine.partialField(rawSoFar, "english_prompt");
                String persian = PromptEngine.partialField(rawSoFar, "persian_prompt");
                if (english == null && persian == null) {
                    return;
                }
                resultCard.setVisibility(View.VISIBLE);
                badgeCategory.setText(R.string.streaming_hint);
                badgeSubcategory.setVisibility(View.GONE);
                purpose.setVisibility(View.GONE);
                if (english != null) {
                    outputEnglish.setText(english);
                }
                if (persian != null) {
                    outputPersian.setText(persian);
                }
            }

            @Override
            public void onSuccess(String rawText, JSONObject usage) {
                job = null;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                resetCompileButton();
                render(PromptEngine.parse(rawText));
                store.recordUsage(model, usage);
                store.addHistory(model, submittedInput, rawText);
            }

            @Override
            public void onFailure(int kind, String detail) {
                job = null;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                resetCompileButton();
                if (kind == GeminiClient.ERR_CANCELLED) {
                    Ui.toast(MainActivity.this, getString(R.string.cancelled));
                    return;
                }
                showError(messageFor(kind, detail));
            }
        });
    }

    private void resetCompileButton() {
        compileButton.setText(R.string.btn_compile);
    }

    private void render(PromptResult result) {
        resultCard.setVisibility(View.VISIBLE);
        badgeCategory.setText(ModelCatalog.categoryLabel(result.detectedCategory));
        if (result.detectedSubcategory.isEmpty()) {
            badgeSubcategory.setVisibility(View.GONE);
        } else {
            badgeSubcategory.setVisibility(View.VISIBLE);
            badgeSubcategory.setText(result.detectedSubcategory);
        }
        if (result.detectedPurpose.isEmpty()) {
            purpose.setVisibility(View.GONE);
        } else {
            purpose.setVisibility(View.VISIBLE);
            purpose.setText(result.detectedPurpose);
        }
        outputEnglish.setText(result.englishPrompt);
        outputPersian.setText(result.persianPrompt);

        // Empty analysis blocks are hidden: four "nothing here" cards are pure noise on a phone.
        bindBlock(blockRequirements, result.preservedRequirements);
        bindBlock(blockAssumptions, result.assumptions);
        bindBlock(blockMissing, result.missingInformation);
        bindBlock(blockSuggestions, result.suggestions);
    }

    private void bindBlock(LinearLayout block, List<String> items) {
        LinearLayout body = block.findViewById(R.id.block_body);
        if (items.isEmpty()) {
            block.setVisibility(View.GONE);
            body.removeAllViews();
            return;
        }
        block.setVisibility(View.VISIBLE);
        Ui.fillBullets(body, this, items);
    }

    private void showError(String message) {
        errorCard.setVisibility(View.VISIBLE);
        errorText.setText(message);
    }

    private void hideError() {
        errorCard.setVisibility(View.GONE);
    }

    private String messageFor(int kind, String detail) {
        int resource;
        switch (kind) {
            case GeminiClient.ERR_KEY:
                resource = R.string.err_need_key;
                break;
            case GeminiClient.ERR_OFFLINE:
                resource = R.string.err_offline;
                break;
            case GeminiClient.ERR_BLOCKED:
                resource = R.string.err_blocked;
                break;
            case GeminiClient.ERR_RATE_LIMIT:
                resource = R.string.err_rate_limit;
                break;
            default:
                resource = R.string.err_http;
                break;
        }
        String message = getString(resource);
        if (kind == GeminiClient.ERR_OFFLINE || detail == null || detail.trim().isEmpty()) {
            return message;
        }
        return message + "\n\n" + detail;
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------- dialogs

    private void showMenu() {
        final String[] items = {
                getString(R.string.menu_settings),
                getString(R.string.menu_usage),
                getString(R.string.menu_history),
                getString(R.string.menu_clear_input),
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openSettings();
                        } else if (which == 1) {
                            showUsage();
                        } else if (which == 2) {
                            showHistory();
                        } else {
                            input.setText("");
                            resultCard.setVisibility(View.GONE);
                            hideError();
                        }
                    }
                })
                .show();
    }

    private List<ModelInfo> usableModels() {
        List<ModelInfo> usable = new ArrayList<>();
        for (ModelInfo model : ModelCatalog.all(store)) {
            if (model.generate) {
                usable.add(model);
            }
        }
        return usable;
    }

    private void showModelPicker() {
        final List<ModelInfo> usable = usableModels();
        final String[] labels = new String[usable.size()];
        int selected = 0;
        for (int index = 0; index < usable.size(); index++) {
            labels[index] = usable.get(index).name;
            if (usable.get(index).id.equals(config.model)) {
                selected = index;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_model)
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        config.model = usable.get(which).id;
                        store.saveConfig(config);
                        modelButton.setText(ModelCatalog.nameFor(config.model));
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showUsage() {
        List<ModelInfo> models = ModelCatalog.all(store);
        JSONObject usage = UsageStats.compute(store, models);
        JSONObject today = usage.optJSONObject("today");
        long totalTokens = today == null ? 0 : today.optLong("total", 0);
        long requests = today == null ? 0 : today.optLong("requests", 0);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.label(this, getString(R.string.usage_today), R.color.muted, 12f));
        TextView summary = Ui.label(this,
                Ui.formatTokens(totalTokens) + " " + getString(R.string.usage_tokens)
                        + "  ·  " + Ui.formatInt(requests) + " " + getString(R.string.usage_requests),
                R.color.text, 18f);
        summary.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        root.addView(summary);

        ModelInfo selectedModel = ModelCatalog.find(config.model);
        if (selectedModel == null) {
            for (ModelInfo model : models) {
                if (model.id.equals(config.model)) {
                    selectedModel = model;
                    break;
                }
            }
        }
        if (selectedModel != null) {
            JSONObject stat = findStat(usage.optJSONArray("models"), selectedModel.id);
            long todayRequests = stat == null ? 0 : stat.optLong("today_requests", 0);
            long minuteRequests = stat == null ? 0 : stat.optLong("minute_requests", 0);
            long minuteTokens = stat == null ? 0 : stat.optLong("minute_tokens", 0);

            TextView name = Ui.label(this, selectedModel.name, R.color.accent, 14f);
            name.setPadding(0, Ui.dp(this, 14), 0, 0);
            root.addView(name);
            root.addView(Ui.meterRow(this, getString(R.string.usage_rpd),
                    todayRequests, selectedModel.rpd));
            root.addView(Ui.meterRow(this, getString(R.string.usage_tpm),
                    minuteTokens, selectedModel.tpm));
            root.addView(Ui.meterRow(this, getString(R.string.usage_rpm),
                    minuteRequests, selectedModel.rpm));
        }

        JSONArray stats = usage.optJSONArray("models");
        if (stats != null) {
            for (int index = 0; index < stats.length(); index++) {
                JSONObject stat = stats.optJSONObject(index);
                if (stat == null || stat.optLong("today_requests", 0) <= 0) {
                    continue;
                }
                TextView row = Ui.label(this,
                        stat.optString("name") + "  ·  "
                                + Ui.formatInt(stat.optLong("today_requests", 0)) + " "
                                + getString(R.string.usage_requests) + "  ·  "
                                + Ui.formatTokens(stat.optLong("today_tokens", 0)) + " "
                                + getString(R.string.usage_tokens),
                        R.color.muted, 12f);
                row.setPadding(0, Ui.dp(this, 8), 0, 0);
                root.addView(row);
            }
        }
        if (requests == 0) {
            TextView empty = Ui.label(this, getString(R.string.usage_empty), R.color.muted, 12f);
            empty.setPadding(0, Ui.dp(this, 16), 0, 0);
            root.addView(empty);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenHeight() * 0.7f)));
        new AlertDialog.Builder(this)
                .setTitle(R.string.usage_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showHistory() {
        final JSONArray history = store.loadHistory();
        final List<JSONObject> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("MM/dd HH:mm", Locale.forLanguageTag("fa-IR"));
        for (int index = 0; index < history.length(); index++) {
            JSONObject entry = history.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            entries.add(entry);
            String first = entry.optString("input", "").replace('\n', ' ');
            if (first.length() > 60) {
                first = first.substring(0, 60) + "…";
            }
            labels.add(format.format(new Date(entry.optLong("t", 0))) + "  ·  " + first);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.history_title)
                .setPositiveButton(R.string.close, null);
        if (entries.isEmpty()) {
            builder.setMessage(R.string.history_empty);
        } else {
            builder.setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    JSONObject entry = entries.get(which);
                    String raw = entry.optString("raw", "");
                    input.setText(entry.optString("input", ""));
                    hideError();
                    render(PromptEngine.parse(raw));
                }
            });
            builder.setNeutralButton(R.string.history_clear, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    store.clearHistory();
                }
            });
        }
        builder.show();
    }

    private JSONObject findStat(JSONArray stats, String modelId) {
        if (stats == null) {
            return null;
        }
        for (int index = 0; index < stats.length(); index++) {
            JSONObject stat = stats.optJSONObject(index);
            if (stat != null && modelId.equals(stat.optString("id"))) {
                return stat;
            }
        }
        return null;
    }

    private float screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    // -------------------------------------------------------------- output

    private void copyToClipboard(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Prompt Engine", text));
            Ui.toast(this, getString(R.string.copied));
        } catch (Throwable ignored) {
            Ui.toast(this, getString(R.string.copy_failed));
        }
    }

    private void shareText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.share_title)));
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}

package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The single screen: Persian input, compiled English prompt, analysis, copy and share. */
public class MainActivity extends Activity {

    private Store store;
    private Config config;
    private GeminiClient.Job job;
    private String submittedInput = "";
    private String clarifyInput = "";

    // Discovery-phase state: the interview runs as a chain of dialogs, and every callback
    // checks its epoch so a cancelled interview can never trigger a stale compile.
    private ClarifyResult pendingClarify;
    private final List<String[]> collectedAnswers = new ArrayList<>();
    private int clarifyIndex;
    private int discoveryEpoch;
    private boolean discoveryInFlight;
    private boolean discoveryFailedOnce;

    private EditText input;
    private Button compileButton;
    private Button modelButton;
    private ProgressBar progress;
    private ScrollView scrollView;
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
        scrollView = findViewById(R.id.scroll);
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
        findViewById(R.id.btn_new_request).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                newRequest();
            }
        });
        findViewById(R.id.btn_memory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openMemory();
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

        // A shared or selected text wins over the stored session: the user came here to compile it.
        if (!handleIntent(getIntent())) {
            restoreLastSession();
        }
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

        // The memory screen leaves its action in Store instead of returning an activity result:
        // onResume always runs when it closes, so the hand-off cannot be missed.
        String pending = store.takePendingAction();
        if (pending.isEmpty()) {
            return;
        }
        if (Store.ACTION_NEW.equals(pending)) {
            newRequest();
            return;
        }
        long timestamp = Store.recallTimestamp(pending);
        if (timestamp == 0L) {
            return;
        }
        JSONObject entry = store.findHistory(timestamp);
        if (entry != null) {
            recallForEditing(entry);
        }
    }

    /** Puts a stored request back in the editor so it can be changed and compiled again. */
    private void recallForEditing(JSONObject entry) {
        if (job != null) {
            job.cancel();
            job = null;
            progress.setVisibility(View.GONE);
            resetCompileButton();
        }
        discoveryEpoch++;
        pendingClarify = null;
        collectedAnswers.clear();
        submittedInput = entry.optString("input", "");
        input.setText(submittedInput);
        input.setSelection(input.getText().length());
        hideError();
        render(PromptEngine.parse(entry.optString("raw", "")));
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.editing_from_memory);
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(View.FOCUS_UP);
            }
        });
        input.post(new Runnable() {
            @Override
            public void run() {
                input.requestFocus();
                showKeyboard();
            }
        });
        Ui.toast(this, getString(R.string.memory_restored));
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

    /** Returns true when the intent carried text to compile. */
    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        String incoming = null;
        if (Intent.ACTION_SEND.equals(action)) {
            incoming = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            incoming = selected == null ? null : selected.toString();
        }
        if (incoming == null || incoming.trim().isEmpty()) {
            return false;
        }
        input.setText(incoming.trim());
        input.setSelection(input.getText().length());
        startCompile();
        return true;
    }

    // ------------------------------------------------------------ new request

    /** Clears the screen and starts a fresh request, forgetting the stored session. */
    private void newRequest() {
        if (job != null) {
            job.cancel();
            job = null;
        }
        discoveryEpoch++;
        pendingClarify = null;
        collectedAnswers.clear();
        submittedInput = "";
        input.setText("");
        resultCard.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        resetCompileButton();
        store.clearLastSession();
        input.requestFocus();
        showKeyboard();
    }

    private void showKeyboard() {
        try {
            InputMethodManager keyboard =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    /** Puts the previous session back on screen when the app is reopened. */
    private void restoreLastSession() {
        JSONObject session = store.loadLastSession();
        if (session == null) {
            return;
        }
        String raw = session.optString("raw", "");
        if (raw.trim().isEmpty()) {
            return;
        }
        submittedInput = session.optString("input", "");
        input.setText(submittedInput);
        render(PromptEngine.parse(raw));
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
        if (discoveryInFlight) {
            Ui.toast(this, getString(R.string.busy));
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

        hideError();
        if (discoveryFailedOnce) {
            // The last discovery attempt failed. Do not trap the user in a broken loop:
            // this tap compiles directly, and the next one asks again.
            discoveryFailedOnce = false;
            compileNow(text, null);
            return;
        }
        beginDiscovery(text);
    }

    /** Phase 1+2: analyse the request, then ask only what genuinely matters. */
    private void beginDiscovery(final String text) {
        discoveryInFlight = true;
        clarifyInput = text;
        final int epoch = ++discoveryEpoch;
        resultCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.clarify_status);

        final String model = config.model;
        GeminiClient.clarify(config, text, new GeminiClient.ClarifyCallback() {
            @Override
            public void onReady(final ClarifyResult result, String rawText, final JSONObject usage) {
                if (epoch != discoveryEpoch) {
                    return;
                }
                discoveryInFlight = false;
                if (usage != null && usage.length() > 0) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            store.recordUsage(model, usage);
                        }
                    }, "clarify-persist").start();
                }

                // A degraded or already-clear request must never trap the user in questions.
                if (result.degraded || !result.hasCriticalAmbiguity || result.questions.isEmpty()) {
                    compileNow(clarifyInput, null);
                    return;
                }
                pendingClarify = result;
                clarifyIndex = 0;
                collectedAnswers.clear();
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                askNextQuestion();
            }

            @Override
            public void onFailure(int kind, String detail) {
                if (epoch != discoveryEpoch) {
                    return;
                }
                discoveryInFlight = false;
                discoveryFailedOnce = true;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                String message = getString(R.string.discovery_failed);
                if (detail != null && !detail.trim().isEmpty()) {
                    message = message + "\n\n" + detail;
                }
                showError(message);
            }
        });
    }

    /** Phase 2, one question at a time; then phase 3 compiles with the collected answers. */
    private void askNextQuestion() {
        // Every answer path (option chip, skip, free text) arrives here, so closing the current
        // dialog here is what stops the question screens from stacking on top of each other - and
        // it is also what closes the last one before compilation starts.
        dismissQuestionDialog();
        if (pendingClarify == null || clarifyIndex >= pendingClarify.questions.size()) {
            final List<String[]> answers = new ArrayList<>(collectedAnswers);
            pendingClarify = null;
            compileNow(clarifyInput, answers);
            return;
        }
        showQuestionDialog(pendingClarify.questions.get(clarifyIndex));
    }

    private void showQuestionDialog(final ClarifyResult.Question question) {
        int total = pendingClarify == null ? 0 : pendingClarify.questions.size();

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, pad, pad, Ui.dp(this, 8));

        if (clarifyIndex == 0 && pendingClarify != null
                && !pendingClarify.reasoningFa.isEmpty()) {
            TextView reasoning = Ui.label(this, pendingClarify.reasoningFa,
                    R.color.muted, 13f);
            reasoning.setPadding(0, 0, 0, Ui.dp(this, 12));
            root.addView(reasoning);
        }

        TextView text = Ui.label(this, question.question, R.color.text, 15f);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(text);

        if (!question.why.isEmpty()) {
            TextView why = Ui.label(this, getString(R.string.clarify_why_prefix) + " "
                    + question.why, R.color.muted, 12f);
            why.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
            root.addView(why);
        }

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = Ui.dp(this, 8);

        for (final String option : question.options) {
            Button choice = new Button(this, null, 0);
            choice.setTextAppearance(this, R.style.BtnGhost);
            choice.setBackgroundResource(R.drawable.bg_btn_ghost);
            choice.setAllCaps(false);
            choice.setGravity(android.view.Gravity.CENTER);
            choice.setText(option);
            choice.setLayoutParams(new LinearLayout.LayoutParams(buttonParams));
            choice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    recordAnswer(question.question, option);
                }
            });
            root.addView(choice);
        }

        Button free = new Button(this, null, 0);
        free.setTextAppearance(this, R.style.BtnGhost);
        free.setBackgroundResource(R.drawable.bg_btn_chip);
        free.setAllCaps(false);
        free.setText(question.options.isEmpty()
                ? getString(R.string.answer_free) + " (الزامی)"
                : getString(R.string.answer_free));
        free.setLayoutParams(new LinearLayout.LayoutParams(buttonParams));
        free.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                promptFreeAnswer(question);
            }
        });
        root.addView(free);

        Button skip = new Button(this, null, 0);
        skip.setTextAppearance(this, R.style.BtnGhost);
        skip.setBackgroundResource(R.drawable.bg_btn_chip);
        skip.setAllCaps(false);
        skip.setTextColor(getColor(R.color.muted));
        skip.setText(getString(R.string.skip_question) + " (نامی‌شده به حدس مدل)");
        skip.setLayoutParams(new LinearLayout.LayoutParams(buttonParams));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recordAnswer(question.question, null);
            }
        });
        root.addView(skip);

        // Directive action: hand the rest to the model and compile now with what we have.
        Button skipRest = new Button(this, null, 0);
        skipRest.setTextAppearance(this, R.style.BtnGhost);
        skipRest.setBackgroundResource(R.drawable.bg_btn_chip);
        skipRest.setAllCaps(false);
        skipRest.setTextColor(getColor(R.color.muted));
        skipRest.setText(R.string.skip_remaining);
        skipRest.setLayoutParams(new LinearLayout.LayoutParams(buttonParams));
        skipRest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final List<String[]> answers = new ArrayList<>(collectedAnswers);
                    pendingClarify = null;
                    dismissQuestionDialog();
                    compileNow(clarifyInput, answers);
            }
        });
        root.addView(skipRest);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.clarify_question_progress,
                        clarifyIndex + 1, total))
                .setView(scroll)
                .setNegativeButton(R.string.interview_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        abortInterview();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        abortInterview();
                    }
                })
                .show();
        questionDialog = dialog;
    }

    private AlertDialog questionDialog;

    /** Closes the open interview dialog. Safe to call when none is showing. */
    private void dismissQuestionDialog() {
        AlertDialog dialog = questionDialog;
        questionDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void promptFreeAnswer(final ClarifyResult.Question question) {
        final EditText field = new EditText(this);
        field.setTextAppearance(this, R.style.InputField);
        field.setBackgroundResource(R.drawable.bg_input);
        field.setTextColor(getColor(R.color.text));
        field.setHintTextColor(getColor(R.color.hint));
        field.setHint(R.string.free_answer_hint);
        field.setMinLines(2);
        field.setTextDirection(View.TEXT_DIRECTION_RTL);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        holder.setPadding(pad, Ui.dp(this, 8), pad, 0);
        holder.addView(field, Ui.matchWrap());

        new AlertDialog.Builder(this)
                .setTitle(question.question)
                .setView(holder)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        // This dialog closes itself on a button press; recordAnswer() then closes
                        // the question dialog open behind it.
                        recordAnswer(question.question, field.getText().toString());
                    }
                })
                .setNegativeButton(R.string.interview_cancel, null)
                .show();
    }

    private void recordAnswer(String question, String answer) {
        if (answer != null && !answer.trim().isEmpty()) {
            collectedAnswers.add(new String[]{question, answer.trim()});
        }
        clarifyIndex++;
        askNextQuestion();
    }

    private void abortInterview() {
        dismissQuestionDialog();
        if (pendingClarify == null) {
            return;
        }
        pendingClarify = null;
        collectedAnswers.clear();
        discoveryEpoch++;
        progress.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        Ui.toast(this, getString(R.string.interview_cancelled));
    }

    /** Phase 3: stream the compiled prompt, feeding the user's answers in as requirements. */
    private void compileNow(final String text, final List<String[]> answers) {
        submittedInput = text;
        hideError();
        resultCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.status_connecting);
        compileButton.setText(R.string.btn_cancel);

        final String model = config.model;
        job = GeminiClient.compile(config, text, answers, new GeminiClient.CompileCallback() {
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

                final PromptResult result = PromptEngine.parse(rawText);
                render(result);

                final String inputText = submittedInput;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        store.recordUsage(model, usage);
                        store.addHistory(model, inputText, rawText,
                                result.detectedCategory, result.detectedPurpose);
                        store.saveLastSession(inputText, rawText, model);
                    }
                }, "prompt-persist").start();
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
                            openMemory();
                        } else {
                            newRequest();
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
        if (Ui.copy(this, text)) {
            Ui.toast(this, getString(R.string.copied));
        } else {
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

    private void openMemory() {
        startActivity(new Intent(this, HistoryActivity.class));
    }
}

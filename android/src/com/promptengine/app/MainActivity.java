package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
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
    /** The look this activity was built with; a change means it must be rebuilt. */
    private String appliedLook = ThemePreset.DEFAULT;
    private Config config;
    private AiClient.Job job;
    private String submittedInput = "";
    private String clarifyInput = "";

    // Discovery-phase state: the interview runs as a chain of dialogs, and every callback
    // checks its epoch so a cancelled interview can never trigger a stale compile.
    private ClarifyResult pendingClarify;
    private int discoveryEpoch;
    private boolean discoveryInFlight;
    /**
     * The request text whose analysis failed, if any. Scoped to the request on purpose: a global
     * flag made one failure silently skip the interview for the *next*, unrelated request.
     */
    private String discoveryFailedFor = "";
    /** Set by the menu when the user wants questions even though the request looks clear. */
    private boolean forceInterview;

    /** Set when the interview finished: its answers are kept so a retry never re-asks. */
    private boolean interviewDone;

    // Interview surface: one dialog for the whole interview, with progress, in-place answers
    // and a review page, instead of a window destroyed and rebuilt for every question.
    private List<String[]> answers = new ArrayList<>();
    private int interviewIndex;
    private Dialog interviewDialog;
    private LinearLayout interviewBody;
    private ProgressBar interviewProgress;
    private TextView interviewStepLabel;
    private Button interviewPrimary;
    private Button interviewBack;
    private EditText interviewFree;
    /** Options ticked per question. Multi-select is the norm; a few questions are exclusive. */
    private final java.util.Map<Integer, java.util.LinkedHashSet<String>> optionPicks =
            new java.util.LinkedHashMap<>();
    /** Free text typed per question, kept apart from the ticked options. */
    private final java.util.Map<Integer, String> freeTexts = new java.util.LinkedHashMap<>();

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
        CrashLog.install(this);
        // Must happen before the layout is inflated: an activity's theme is read at inflation.
        appliedLook = ThemePreset.apply(this);
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


    /** False once the screen is finishing or destroyed: no view or dialog may be touched then. */
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The look is chosen in Settings: rebuilding is the only way an activity changes theme.
        // appliedLook is updated BEFORE recreate() so a screen that somehow did not apply the
        // theme on create cannot rebuild itself in a loop.
        String storedLook = ThemePreset.normalize(store.loadLook());
        if (!storedLook.equals(appliedLook)) {
            appliedLook = storedLook;
            recreate();
            return;
        }
        config = store.loadConfig();
        modelButton.setText(selectedModelLabel());

        warnWhenStorageIsCritical();
        showLastCrashIfAny();

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

    private boolean storageWarningShown;
    private boolean crashReportShown;

    /**
     * Surfaces the previous run's crash. Without this a crash is invisible to the user and
     * unreportable, because the app cannot read another app's system logs on a non-rooted phone.
     */
    private void showLastCrashIfAny() {
        if (crashReportShown) {
            return;
        }
        final String report = CrashLog.read(this);
        if (report == null) {
            return;
        }
        crashReportShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(report.length() > 4000 ? report.substring(0, 4000) : report)
                .setNeutralButton(R.string.crash_copy, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(report);
                    }
                })
                .setNegativeButton(R.string.crash_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        CrashLog.clear(MainActivity.this);
                    }
                })
                .setPositiveButton(R.string.crash_dismiss, null)
                .show();
    }

    /**
     * A nearly full phone is the usual reason this app looks like it closes by itself: Android
     * kills processes and every write fails. Saying so once per launch is far better than a
     * mysterious exit with no explanation.
     */
    private void warnWhenStorageIsCritical() {
        if (storageWarningShown || !store.isStorageCritical()) {
            return;
        }
        storageWarningShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_critical_title)
                .setMessage(getString(R.string.storage_critical_body, store.freeMegabytes()))
                .setPositiveButton(R.string.storage_critical_ok, null)
                .show();
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
        answers.clear();
        interviewDone = false;
        clarifyInput = "";
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
        answers.clear();
        submittedInput = "";
        clarifyInput = "";
        interviewDone = false;
        answers.clear();
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
        if (!Providers.anyUsable(Providers.load(store, config.apiKey))) {
            Ui.toast(this, getString(R.string.err_need_key));
            openSettings();
            return;
        }
        if (!isOnline()) {
            Ui.toast(this, getString(R.string.err_offline));
            return;
        }

        // The interview is recorded for this exact request: a retry (for example after fixing
        // the network or waiting out a quota window) compiles straight away with the kept
        // answers instead of running the interrogation again and burning another request.
        if (interviewDone && text.equals(clarifyInput)) {
            Ui.toast(this, getString(R.string.interview_kept));
            compileNow(text, new ArrayList<>(answers));
            return;
        }
        if (!text.equals(clarifyInput)) {
            // The request changed, so a stored interview no longer describes it, and a failure
            // from the previous request must not cost this one its interview.
            interviewDone = false;
            discoveryFailedFor = "";
            answers.clear();
        }

        hideError();
        if (text.equals(discoveryFailedFor)) {
            // This exact request already failed its analysis. Do not trap the user in a broken
            // loop: compile now, say so, and ask again on the next attempt.
            discoveryFailedFor = "";
            forceInterview = false;
            Ui.toast(this, getString(R.string.analysis_skipped_after_failure));
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

        final String model = Providers.modelIdOf(config.model, ModelCatalog.DEFAULT_MODEL);
        AiClient.analyze(config, store, text, forceInterview, new AiClient.AnalyzeCallback() {
            @Override
            public void onReady(final ClarifyResult result, String rawText, final JSONObject usage,
                                String source) {
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
                if (result.degraded) {
                    // Say why the interview was skipped: silence here looks like a broken feature.
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.analysis_unreadable);
                    Ui.toast(MainActivity.this, getString(R.string.analysis_unreadable));
                }
                if (result.degraded || !result.hasCriticalAmbiguity || result.questions.isEmpty()) {
                    forceInterview = false;
                    if (!result.degraded) {
                        // The model judged the request clear. That is a real decision, so say it
                        // and leave a way to insist on questions (menu -> پرسش‌های بیشتر).
                        status.setVisibility(View.VISIBLE);
                        status.setText(R.string.interview_not_needed);
                        Ui.toast(MainActivity.this, getString(R.string.interview_not_needed));
                    }
                    compileNow(clarifyInput, null);
                    return;
                }
                pendingClarify = result;
                interviewIndex = 0;
                answers.clear();
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                openInterview();
            }

            @Override
            public void onFailure(int kind, String detail) {
                if (epoch != discoveryEpoch) {
                    return;
                }
                discoveryInFlight = false;
                discoveryFailedFor = clarifyInput;
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

    // ---------------------------------------------------------- interview surface

    /**
     * The interview is one screen, not a chain of dialogs: progress is always visible, answers are
     * edited in place, and the user can walk back. The previous dialog-per-question design
     * destroyed and rebuilt a window for every question, showed nine controls at once, and left no
     * sense of how much was left.
     */
    private void openInterview() {
        if (pendingClarify == null || pendingClarify.questions.isEmpty()) {
            compileNow(clarifyInput, null);
            return;
        }
        answers = new ArrayList<>();
        optionPicks.clear();
        freeTexts.clear();
        for (int index = 0; index < pendingClarify.questions.size(); index++) {
            answers.add(new String[]{"", ""});
        }

        LinearLayout root = Ui.column(this);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18));

        // --- header: what this is, and where we are.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setLayoutParams(Ui.matchWrap());
        TextView title = Ui.label(this, getString(R.string.interview_title), R.attr.c_text, 18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        Button overview = new Button(this, null, 0);
        overview.setTextAppearance(this, R.style.BtnIcon);
        overview.setBackgroundResource(R.drawable.bg_icon_button);
        overview.setText("⋮");
        overview.setTextSize(18f);
        overview.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44)));
        header.addView(overview);
        root.addView(header);

        interviewProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        interviewProgress.setProgressDrawable(getDrawable(R.drawable.bg_progress));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        progressParams.topMargin = Ui.dp(this, 12);
        interviewProgress.setLayoutParams(progressParams);
        root.addView(interviewProgress);

        interviewStepLabel = Ui.label(this, "", R.attr.c_muted, 12f);
        interviewStepLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(interviewStepLabel);

        // --- scrollable body: one question, or the review page.
        interviewBody = Ui.column(this);
        ScrollView bodyScroll = new ScrollView(this);
        bodyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bodyScroll.addView(interviewBody);
        root.addView(bodyScroll);

        // --- sticky actions.
        interviewPrimary = new Button(this, null, 0);
        interviewPrimary.setTextAppearance(this, R.style.NavPrimary);
        interviewPrimary.setBackgroundResource(R.drawable.bg_nav_primary);
        interviewPrimary.setAllCaps(false);
        interviewPrimary.setLayoutParams(Ui.matchWrap());
        interviewPrimary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                commitCurrentAndAdvance();
            }
        });

        interviewBack = new Button(this, null, 0);
        interviewBack.setTextAppearance(this, R.style.NavGhost);
        interviewBack.setBackgroundResource(R.drawable.bg_nav_ghost);
        interviewBack.setAllCaps(false);
        interviewBack.setText(R.string.interview_back);
        interviewBack.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        interviewBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (interviewIndex > 0) {
                    bindInterview(interviewIndex - 1);
                }
            }
        });

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barParams = Ui.matchWrap();
        barParams.topMargin = Ui.dp(this, 12);
        actionBar.setLayoutParams(barParams);
        actionBar.addView(interviewBack);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        primaryParams.setMarginStart(Ui.dp(this, 8));
        interviewPrimary.setLayoutParams(primaryParams);
        actionBar.addView(interviewPrimary);
        root.addView(actionBar);

        overview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showInterviewMenu();
            }
        });

        interviewDialog = new Dialog(this);
        interviewDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        interviewDialog.setContentView(root);
        Window window = interviewDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_dialog);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        interviewDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                confirmCancelInterview();
            }
        });
        interviewDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialogInterface, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    if (interviewIndex > 0) {
                        // Back walks the interview instead of throwing the answers away.
                        bindInterview(interviewIndex - 1);
                        return true;
                    }
                    confirmCancelInterview();
                    return true;
                }
                return false;
            }
        });
        interviewDialog.show();
        bindInterview(0);
    }

    /** Draws question {@code index}, or the review page when index is past the last question. */
    private void bindInterview(int index) {
        if (pendingClarify == null) {
            return;
        }
        interviewIndex = index;
        interviewBody.removeAllViews();
        List<ClarifyResult.Question> questions = pendingClarify.questions;
        int total = questions.size();
        boolean review = index >= total;

        interviewProgress.setMax(total + 1);
        interviewProgress.setProgress(index + 1);

        if (review) {
            interviewStepLabel.setText(R.string.interview_review_title);
            interviewPrimary.setText(R.string.interview_compile);
            interviewBack.setVisibility(View.VISIBLE);
            buildReviewPage();
            return;
        }

        ClarifyResult.Question question = questions.get(index);
        interviewStepLabel.setText(getString(R.string.interview_step,
                Ui.formatInt(index + 1), Ui.formatInt(total), slotName(question.id)));
        interviewPrimary.setText(index == total - 1
                ? getString(R.string.interview_compile) : getString(R.string.interview_next));
        interviewBack.setEnabled(index > 0);
        interviewBack.setAlpha(index > 0 ? 1f : 0.4f);
        interviewBack.setText(index > 0
                ? getString(R.string.interview_back) : getString(R.string.interview_back_first));

        // Reasoning is orientation for the first page only: repeating it on every page is noise.
        if (index == 0 && !pendingClarify.reasoningFa.isEmpty()) {
            TextView reasoning = Ui.label(this, pendingClarify.reasoningFa, R.attr.c_muted, 12f);
            reasoning.setLineSpacing(Ui.dp(this, 3), 1f);
            reasoning.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            reasoning.setBackgroundResource(R.drawable.bg_well);
            interviewBody.addView(reasoning, Ui.matchWrap());
        }

        TextView title = Ui.label(this, question.question, R.attr.c_text, 20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLineSpacing(Ui.dp(this, 4), 1f);
        title.setPadding(0, Ui.dp(this, 16), 0, 0);
        interviewBody.addView(title, Ui.matchWrap());

        if (!question.why.isEmpty()) {
            TextView why = Ui.label(this, question.why, R.attr.c_muted, 12f);
            why.setLineSpacing(Ui.dp(this, 3), 1f);
            LinearLayout.LayoutParams whyParams = Ui.matchWrap();
            whyParams.topMargin = Ui.dp(this, 6);
            interviewBody.addView(why, whyParams);
        }

        TextView pickHint = Ui.label(this, getString(question.multi
                        ? R.string.interview_multi_hint : R.string.interview_single_hint),
                R.attr.c_hint_readable, 11f);
        LinearLayout.LayoutParams hintParams = Ui.matchWrap();
        hintParams.topMargin = Ui.dp(this, 10);
        pickHint.setLayoutParams(hintParams);
        interviewBody.addView(pickHint);

        final java.util.LinkedHashSet<String> picked = picksFor(index, question);
        for (final String option : question.options) {
            final boolean recommended = sameOption(option, question.recommended);
            final Button row = new Button(this, null, 0);
            row.setTextAppearance(this, recommended ? R.style.OptionRow_Recommended : R.style.OptionRow);
            row.setAllCaps(false);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            LinearLayout.LayoutParams rowParams = Ui.matchWrap();
            rowParams.topMargin = Ui.dp(this, 8);
            row.setLayoutParams(rowParams);
            paintOption(row, option, picked.contains(option), recommended);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (question.multi) {
                        if (!picked.remove(option)) {
                            picked.add(option);
                        }
                    } else {
                        // Mutually exclusive: ticking one clears the rest.
                        boolean wasPicked = picked.contains(option);
                        picked.clear();
                        if (!wasPicked) {
                            picked.add(option);
                        }
                    }
                    paintOption(row, option, picked.contains(option), recommended);
                    answers.get(interviewIndex)[1] = join(picked);
                }
            });
            interviewBody.addView(row);
        }

        // Free text lives on the same screen: no second dialog stacked on top.
        EditText free = new EditText(this);
        free.setTextAppearance(this, R.style.InputField);
        free.setBackgroundResource(R.drawable.bg_input);
        free.setTextColor(Ui.color(this, R.attr.c_text));
        free.setHintTextColor(Ui.color(this, R.attr.c_hint));
        free.setHint(question.options.isEmpty()
                ? getString(R.string.interview_free_required) : getString(R.string.interview_free));
        String typed = freeTexts.get(index);
        free.setText(typed == null ? "" : typed);
        free.setMinLines(2);
        // setTextAppearance only carries text attributes; padding and height must be set here.
        free.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        free.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        free.setMinHeight(Ui.dp(this, 48));
        free.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams freeParams = Ui.matchWrap();
        freeParams.topMargin = Ui.dp(this, 14);
        interviewBody.addView(free, freeParams);
        interviewFree = free;
    }

    /**
     * The options ticked for a question. Going back restores what was chosen; a fresh question
     * starts with the recommendation ticked, so accepting the suggestion is a single tap.
     */
    private java.util.LinkedHashSet<String> picksFor(int index, ClarifyResult.Question question) {
        java.util.LinkedHashSet<String> picked = optionPicks.get(index);
        if (picked != null) {
            return picked;
        }
        picked = new java.util.LinkedHashSet<>();
        String stored = answers.get(index)[1];
        if (!stored.isEmpty()) {
            for (String option : question.options) {
                if (stored.contains(option)) {
                    picked.add(option);
                }
            }
        }
        if (picked.isEmpty() && question.options.contains(question.recommended)) {
            picked.add(question.recommended);
        }
        optionPicks.put(index, picked);
        return picked;
    }

    /** A tick box in front of the label: the state must be readable without colour alone. */
    private void paintOption(Button row, String option, boolean checked, boolean recommended) {
        String label = (checked ? "☑ " : "☐ ") + option;
        if (recommended) {
            label = getString(R.string.interview_recommended_badge) + " · " + label;
        }
        row.setText(label);
        row.setBackgroundResource(checked ? R.drawable.bg_option_primary : R.drawable.bg_option);
        if (checked && !recommended) {
            row.setTextColor(Ui.color(this, R.attr.c_on_accent));
        } else {
            row.setTextColor(Ui.color(this, recommended ? R.attr.c_accent : R.attr.c_text));
        }
    }

    private static String join(java.util.Collection<String> picked) {
        StringBuilder out = new StringBuilder();
        for (String option : picked) {
            if (out.length() > 0) {
                out.append("، ");
            }
            out.append(option);
        }
        return out.toString();
    }

    /** Writes the current screen's answer, then moves on (or to the review page). */
    private void commitCurrentAndAdvance() {
        if (pendingClarify == null) {
            return;
        }
        List<ClarifyResult.Question> questions = pendingClarify.questions;
        if (interviewIndex >= questions.size()) {
            finishInterview();
            return;
        }
        String typed = interviewFree == null ? "" : interviewFree.getText().toString().trim();
        freeTexts.put(interviewIndex, typed);
        String[] slot = answers.get(interviewIndex);
        slot[0] = questions.get(interviewIndex).question;
        String picked = join(picksFor(interviewIndex, questions.get(interviewIndex)));
        if (!picked.isEmpty() && !typed.isEmpty()) {
            slot[1] = picked + " | " + typed;
        } else if (!picked.isEmpty()) {
            slot[1] = picked;
        } else if (!typed.isEmpty()) {
            slot[1] = typed;
        }
        if (slot[1].isEmpty()) {
            Ui.toast(this, getString(R.string.interview_answer_needed));
            return;
        }
        if (interviewIndex + 1 < questions.size()) {
            bindInterview(interviewIndex + 1);
        } else {
            bindInterview(questions.size());
        }
    }

    private void buildReviewPage() {
        TextView hint = Ui.label(this, getString(R.string.interview_review_hint), R.attr.c_muted, 12f);
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        interviewBody.addView(hint, Ui.matchWrap());

        List<ClarifyResult.Question> questions = pendingClarify.questions;
        for (int index = 0; index < questions.size(); index++) {
            final int target = index;
            final String[] slot = answers.get(index);
            LinearLayout row = Ui.column(this);
            row.setBackgroundResource(R.drawable.bg_section);
            int pad = Ui.dp(this, 12);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams rowParams = Ui.matchWrap();
            rowParams.topMargin = Ui.dp(this, 8);
            row.setLayoutParams(rowParams);

            TextView question = Ui.label(this, slot[0], R.attr.c_muted, 12f);
            row.addView(question);
            TextView answer = Ui.label(this,
                    slot[1].isEmpty() ? getString(R.string.interview_unanswered) : slot[1],
                    R.attr.c_text, 14f);
            answer.setPadding(0, Ui.dp(this, 4), 0, 0);
            row.addView(answer);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    bindInterview(target);
                }
            });
            interviewBody.addView(row);
        }
    }

    private void showInterviewMenu() {
        final String[] items = {
                getString(R.string.interview_accept_all),
                getString(R.string.interview_skip_rest),
                getString(R.string.interview_cancel),
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            acceptAllRecommendations();
                        } else if (which == 1) {
                            finishInterview();
                        } else {
                            confirmCancelInterview();
                        }
                    }
                })
                .show();
    }

    /** Given to the model as an explicit instruction, never dropped silently. */
    private void acceptAllRecommendations() {
        if (pendingClarify == null) {
            return;
        }
        List<ClarifyResult.Question> questions = pendingClarify.questions;
        for (int index = 0; index < questions.size(); index++) {
            String[] slot = answers.get(index);
            slot[0] = questions.get(index).question;
            if (slot[1].isEmpty()) {
                String recommended = questions.get(index).recommended;
                slot[1] = recommended.isEmpty() ? SystemPrompt.UNKNOWN_ANSWER : recommended;
            }
        }
        finishInterview();
    }

    private void finishInterview() {
        if (pendingClarify == null) {
            return;
        }
        List<String[]> collected = new ArrayList<>();
        for (String[] slot : answers) {
            if (!slot[1].isEmpty()) {
                collected.add(new String[]{slot[0], slot[1]});
            }
        }
        // The interview is recorded for this exact request: a retry compiles with these answers.
        interviewDone = true;
        pendingClarify = null;
        closeInterview();
        compileNow(clarifyInput, collected);
    }

    private void confirmCancelInterview() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.interview_cancel_confirm)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        abortInterview();
                    }
                })
                .setPositiveButton(R.string.interview_continue, null)
                .show();
    }

    private void closeInterview() {
        Dialog dialog = interviewDialog;
        interviewDialog = null;
        interviewFree = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void abortInterview() {
        closeInterview();
        if (pendingClarify == null) {
            return;
        }
        pendingClarify = null;
        answers = new ArrayList<>();
        discoveryEpoch++;
        progress.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        Ui.toast(this, getString(R.string.interview_cancelled));
    }

    /** Compares answers the way a Persian user reads them: same words, ignoring ZWNJ and spacing. */
    private static boolean sameOption(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value.replace("\u200c", "").replace("\u200f", "")
                .replace("\u200e", "").trim().replaceAll("\\s+", " ");
    }

    /** G/D/E/C/P/U mapped to a Persian step name. */
    private static String slotName(String id) {
        String slot = id == null || id.isEmpty() ? "G" : id.substring(0, 1).toUpperCase();
        switch (slot) {
            case "G": return "هدف";
            case "S": return "معیار موفقیت";
            case "D": return "داده‌ها";
            case "E": return "محل اجرا";
            case "C": return "ممنوعیت‌ها";
            case "P": return "اولویت‌ها";
            case "U": return "کاربر";
            default: return "سؤال";
        }
    }

    /**
     * Phase 3: stream the compiled prompt, feeding the user's answers in as requirements.
     *
     * The parameter is deliberately NOT called answers: that used to shadow the field of the
     * same name, so the success callback cleared the parameter - which is null on the fast path
     * (a request that needs no interview) and crashed every such compile.
     */
    private void compileNow(final String text, final List<String[]> collectedAnswers) {
        submittedInput = text;
        hideError();
        resultCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.status_connecting);
        compileButton.setText(R.string.btn_cancel);

        final String model = Providers.modelIdOf(config.model, ModelCatalog.DEFAULT_MODEL);
        job = AiClient.compile(config, store, text, collectedAnswers, new AiClient.CompileCallback() {
            @Override
            public void onProgress(String rawSoFar, String source) {
                if (!alive()) {
                    return;
                }
                status.setText(getString(R.string.status_streaming)
                        + " · " + Ui.formatInt(rawSoFar.length())
                        + (source.isEmpty() ? "" : "  ·  " + source));
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
            public void onSuccess(String rawText, JSONObject usage, String source) {
                if (!alive()) {
                    return;
                }
                job = null;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                resetCompileButton();

                final PromptResult result = PromptEngine.parse(rawText);
                render(result);

                // Announce the outcome: the result card sits below the fold, so without this the
                // tool looks like it did nothing after the interview.
                Ui.toast(MainActivity.this, getString(R.string.compile_done));
                scrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.smoothScrollTo(0, resultCard.getTop());
                    }
                });

                // The compile succeeded, so the recorded interview has served its purpose.
                // Always clear the field, never a (possibly null) parameter.
                interviewDone = false;
                if (answers != null) {
                    answers.clear();
                }
                clarifyInput = "";

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
                if (!alive()) {
                    return;
                }
                job = null;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                resetCompileButton();
                if (kind == AiClient.ERR_CANCELLED) {
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
            case AiClient.ERR_KEY:
                resource = R.string.err_need_key;
                break;
            case AiClient.ERR_OFFLINE:
                resource = R.string.err_offline;
                break;
            case AiClient.ERR_BLOCKED:
                resource = R.string.err_blocked;
                break;
            case AiClient.ERR_RATE_LIMIT:
                resource = R.string.err_rate_limit;
                break;
            default:
                resource = R.string.err_http;
                break;
        }
        String message = getString(resource);
        if (kind == AiClient.ERR_OFFLINE || detail == null || detail.trim().isEmpty()) {
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
                getString(R.string.menu_providers),
                getString(R.string.menu_look),
                getString(R.string.menu_more_questions),
                getString(R.string.menu_usage),
                getString(R.string.menu_history),
                getString(R.string.menu_last_crash),
                getString(R.string.menu_clear_input),
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openSettings();
                        } else if (which == 1) {
                            startActivity(new Intent(MainActivity.this, ProvidersActivity.class));
                        } else if (which == 2) {
                            openSettings();
                        } else if (which == 3) {
                            showUsage();
                        } else if (which == 4) {
                            openMemory();
                        } else if (which == 5) {
                            forceInterview = true;
                            startCompile();
                        } else if (which == 6) {
                            crashReportShown = false;
                            showLastCrashIfAny();
                            if (!crashReportShown) {
                                Ui.toast(MainActivity.this, getString(R.string.crash_none));
                            }
                        } else {
                            newRequest();
                        }
                    }
                })
                .show();
    }

    /** Every provider|model pair available for the picker. */
    private List<Providers.ModelChoice> usableModels() {
        return Providers.choices(Providers.load(store, config.apiKey));
    }

    private String selectedModelLabel() {
        String modelId = Providers.modelIdOf(config.model, ModelCatalog.DEFAULT_MODEL);
        String providerId = Providers.providerIdOf(config.model);
        ProviderProfile provider = Providers.byId(Providers.load(store, config.apiKey), providerId);
        String name = ModelCatalog.nameFor(modelId);
        return provider == null ? name : name + " · " + provider.name;
    }

    private void showModelPicker() {
        final List<Providers.ModelChoice> usable = usableModels();
        if (usable.isEmpty()) {
            Ui.toast(this, getString(R.string.err_need_key));
            openSettings();
            return;
        }
        final String[] labels = new String[usable.size()];
        int selected = 0;
        for (int index = 0; index < usable.size(); index++) {
            labels[index] = usable.get(index).label();
            if (usable.get(index).selection().equals(config.model)) {
                selected = index;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_model)
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        config.model = usable.get(which).selection();
                        store.saveConfig(config);
                        modelButton.setText(selectedModelLabel());
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

        root.addView(Ui.label(this, getString(R.string.usage_today), R.attr.c_muted, 12f));
        TextView summary = Ui.label(this,
                Ui.formatTokens(totalTokens) + " " + getString(R.string.usage_tokens)
                        + "  ·  " + Ui.formatInt(requests) + " " + getString(R.string.usage_requests),
                R.attr.c_text, 18f);
        summary.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        root.addView(summary);

        String selectedModelId = Providers.modelIdOf(config.model, ModelCatalog.DEFAULT_MODEL);
        ModelInfo selectedModel = ModelCatalog.find(selectedModelId);
        if (selectedModel == null) {
            for (ModelInfo model : models) {
                if (model.id.equals(selectedModelId)) {
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

            TextView name = Ui.label(this, selectedModel.name, R.attr.c_accent, 14f);
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
                        R.attr.c_muted, 12f);
                row.setPadding(0, Ui.dp(this, 8), 0, 0);
                root.addView(row);
            }
        }
        if (requests == 0) {
            TextView empty = Ui.label(this, getString(R.string.usage_empty), R.attr.c_muted, 12f);
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

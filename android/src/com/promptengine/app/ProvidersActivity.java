package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider, API-key and model management.
 *
 * The editor is a full-screen surface in the app's own theme rather than a platform AlertDialog:
 * an AlertDialog draws a window the app does not own (which is what produced a translucent grey
 * sheet with pale buttons), and its button bar cannot hold three actions without overlapping.
 * Rows are inflated from XML because setTextAppearance() drops padding, height and insets.
 */
public class ProvidersActivity extends Activity {

    private Store store;
    /** The look this activity was built with; a change means it must be rebuilt. */
    private String appliedLook = ThemePreset.DEFAULT;
    private final List<ProviderProfile> providers = new ArrayList<>();
    private ProviderAdapter adapter;
    private TextView emptyView;
    private TextView hintView;

    /** Guards against a second readiness run and lets a finished one be ignored. */
    private volatile Thread probeThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        appliedLook = ThemePreset.apply(this);
        setContentView(R.layout.activity_providers);

        store = new Store(this);
        emptyView = findViewById(R.id.provider_empty);
        hintView = findViewById(R.id.provider_hint);

        ListView list = findViewById(R.id.provider_list);
        adapter = new ProviderAdapter();
        list.setAdapter(adapter);
        list.setItemsCanFocus(false);

        findViewById(R.id.btn_add_provider).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                editProvider(null);
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        probeThread = null;
        super.onDestroy();
    }

    private void reload() {
        providers.clear();
        providers.addAll(Providers.load(store, ""));
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(providers.isEmpty() ? View.VISIBLE : View.GONE);
        hintView.setText(getString(R.string.providers_hint));
    }

    private void persist() {
        Providers.save(store, providers);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ editor

    private void editProvider(final ProviderProfile existing) {
        final ProviderProfile draft = existing == null
                ? new ProviderProfile(Providers.newId(), "", ProviderProfile.KIND_GEMINI, "")
                : copyOf(existing);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundResource(R.drawable.bg_dialog);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // ---- header: title, and delete for an existing provider.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView title = Ui.label(this,
                existing == null ? getString(R.string.provider_add) : getString(R.string.provider_edit),
                R.attr.c_text, 18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        if (existing != null) {
            Button delete = new Button(this, null, 0);
            delete.setTextAppearance(this, R.style.BtnIcon);
            delete.setBackgroundResource(R.drawable.bg_icon_button);
            delete.setContentDescription(getString(R.string.provider_delete));
            delete.setText("🗑");
            delete.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    closeEditor();
                    confirmDelete(existing);
                }
            });
            header.addView(delete);
        }
        shell.addView(header);
        shell.addView(hairline());

        // ---- scrollable body.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 28));

        TextView required = Ui.label(this, getString(R.string.provider_required_note),
                R.attr.c_hint_readable, 11f);
        required.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        body.addView(required);

        sectionLabel(body, getString(R.string.provider_name), true);
        final EditText name = input(body, draft.name);
        final TextView nameError = errorSlot(body);

        sectionLabel(body, getString(R.string.provider_kind), false);
        final Spinner kind = new Spinner(this);
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(this, R.layout.spinner_item,
                new String[]{
                        ProviderProfile.kindLabel(ProviderProfile.KIND_GEMINI),
                        ProviderProfile.kindLabel(ProviderProfile.KIND_OPENAI),
                        ProviderProfile.kindLabel(ProviderProfile.KIND_ANTHROPIC)});
        kindAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        kind.setAdapter(kindAdapter);
        kind.setBackgroundResource(R.drawable.bg_input);
        kind.setMinimumHeight(Ui.dp(this, 48));
        int kindIndex = 0;
        String[] kinds = ProviderProfile.kinds();
        for (int index = 0; index < kinds.length; index++) {
            if (kinds[index].equals(draft.kind)) {
                kindIndex = index;
            }
        }
        kind.setSelection(kindIndex);
        body.addView(kind, Ui.matchWrap());

        sectionLabel(body, getString(R.string.provider_base_url), false);
        final EditText baseUrl = input(body,
                draft.baseUrl.isEmpty() ? ProviderProfile.defaultBaseUrl(draft.kind) : draft.baseUrl);
        final TextView urlHint = Ui.label(this,
                getString(R.string.provider_effective_url,
                        ProviderProfile.defaultBaseUrl(draft.kind)),
                R.attr.c_hint_readable, 11f);
        urlHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(urlHint);
        final TextView urlError = errorSlot(body);

        // Changing the service rewrites an untouched default URL instead of contradicting the user.
        final String[] lastKind = {draft.kind};
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String next = ProviderProfile.kinds()[position];
                String current = baseUrl.getText().toString().trim();
                if (current.isEmpty() || current.equals(ProviderProfile.defaultBaseUrl(lastKind[0]))) {
                    baseUrl.setText(ProviderProfile.defaultBaseUrl(next));
                }
                lastKind[0] = next;
                urlHint.setText(getString(R.string.provider_effective_url,
                        ProviderProfile.defaultBaseUrl(next)));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // ---- API keys.
        final LinearLayout keyRows = new LinearLayout(this);
        keyRows.setOrientation(LinearLayout.VERTICAL);
        final TextView keyLabel = sectionLabel(body, getString(R.string.provider_keys, 0), true);
        note(body, getString(R.string.provider_keys_hint));
        body.addView(keyRows, Ui.matchWrap());
        final TextView keyError = errorSlot(body);
        final Runnable keyCounter = new Runnable() {
            @Override
            public void run() {
                keyLabel.setText(getString(R.string.provider_keys, keyRows.getChildCount()));
            }
        };
        body.addView(chip(getString(R.string.provider_add_key), true, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addValueRow(keyRows, "", getString(R.string.provider_key_hint), keyCounter);
                keyCounter.run();
            }
        }));
        for (String key : draft.apiKeys) {
            addValueRow(keyRows, key, getString(R.string.provider_key_hint), keyCounter);
        }
        if (draft.apiKeys.isEmpty()) {
            addValueRow(keyRows, "", getString(R.string.provider_key_hint), keyCounter);
        }
        keyCounter.run();

        // ---- Models.
        final LinearLayout modelRows = new LinearLayout(this);
        modelRows.setOrientation(LinearLayout.VERTICAL);
        sectionLabel(body, getString(R.string.provider_models), true);
        note(body, getString(R.string.provider_models_hint));
        body.addView(modelRows, Ui.matchWrap());
        final TextView modelError = errorSlot(body);
        LinearLayout modelActions = new LinearLayout(this);
        modelActions.setOrientation(LinearLayout.HORIZONTAL);
        modelActions.setLayoutParams(Ui.matchWrap());
        Button fetch = chip(getString(R.string.provider_fetch_models), true, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fetchModelsInto(kind.getSelectedItemPosition(), lastKind[0],
                        baseUrl.getText().toString(), keyRows, modelRows);
            }
        });
        fetch.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        modelActions.addView(fetch);
        Button addModel = chip(getString(R.string.provider_add_model), false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        addValueRow(modelRows, "", getString(R.string.provider_model_hint), null);
                    }
                });
        addModel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modelActions.addView(addModel);
        body.addView(modelActions, Ui.matchWrap());
        for (String model : draft.models) {
            addValueRow(modelRows, model, getString(R.string.provider_model_hint), null);
        }
        if (draft.models.isEmpty()) {
            addValueRow(modelRows, "", getString(R.string.provider_model_hint), null);
        }

        // ---- readiness test, with progress and a real verdict.
        final TextView probeStatus = note(body, "");
        final ProgressBar probeBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        probeBar.setProgressDrawable(getDrawable(R.drawable.bg_progress));
        probeBar.setMax(100);
        probeBar.setProgress(0);
        probeBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = Ui.matchWrap();
        barParams.topMargin = Ui.dp(this, 10);
        probeBar.setLayoutParams(barParams);
        body.addView(probeBar);
        final Button probe = chip(getString(R.string.provider_test_all), false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        runProbe(name, kind, baseUrl, keyRows, modelRows,
                                probeStatus, probeBar, existing);
                    }
                });
        LinearLayout.LayoutParams probeParams = Ui.matchWrap();
        probeParams.topMargin = Ui.dp(this, 12);
        probe.setLayoutParams(probeParams);
        body.addView(probe);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(body);
        shell.addView(scroll);

        shell.addView(hairline());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 14));
        Button confirm = new Button(this, null, 0);
        confirm.setTextAppearance(this, R.style.BtnPrimary);
        confirm.setBackgroundResource(R.drawable.bg_btn_primary);
        confirm.setAllCaps(false);
        confirm.setText(R.string.provider_save);
        confirm.setLayoutParams(new LinearLayout.LayoutParams(0,
                Ui.dp(this, 48), 1.6f));
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save(name, nameError, kind, baseUrl, urlError, keyRows, keyError,
                        modelRows, modelError, draft, existing);
            }
        });
        actions.addView(confirm);
        Button cancel = new Button(this, null, 0);
        cancel.setTextAppearance(this, R.style.BtnGhost);
        cancel.setBackgroundResource(R.drawable.bg_btn_ghost);
        cancel.setAllCaps(false);
        cancel.setText(R.string.cancel);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0,
                Ui.dp(this, 48), 1f);
        cancelParams.setMarginStart(Ui.dp(this, 8));
        cancel.setLayoutParams(cancelParams);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closeEditor();
            }
        });
        actions.addView(cancel);
        shell.addView(actions);

        openEditor(shell, draft);
    }

    private ProviderProfile copyOf(ProviderProfile source) {
        ProviderProfile copy = new ProviderProfile(source.id, source.name, source.kind, source.baseUrl);
        copy.apiKeys.addAll(source.apiKeys);
        copy.models.addAll(source.models);
        copy.enabled = source.enabled;
        copy.lastProbeAt = source.lastProbeAt;
        copy.lastProbeReady = source.lastProbeReady;
        copy.lastProbeTotal = source.lastProbeTotal;
        return copy;
    }

    // ------------------------------------------------------------- editor shell

    private Dialog editorDialog;

    private void openEditor(LinearLayout shell, final ProviderProfile draft) {
        closeEditor();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(shell);
        Window window = dialog.getWindow();
        if (window != null) {
            // The app's own opaque surface: an AlertDialog ignores the app theme, which is what
            // produced the translucent grey sheet this screen used to show.
            window.setBackgroundDrawableResource(R.drawable.bg_dialog);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.6f;
            window.setAttributes(attributes);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialogInterface, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    closeEditor();
                    return true;
                }
                return false;
            }
        });
        editorDialog = dialog;
        dialog.show();
    }

    private void closeEditor() {
        Dialog dialog = editorDialog;
        editorDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private View hairline() {
        View line = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        line.setLayoutParams(params);
        line.setBackgroundColor(Ui.color(this, R.attr.c_border));
        return line;
    }

    private TextView sectionLabel(LinearLayout root, String text, boolean required) {
        TextView view = Ui.label(this, required ? text + " *" : text, R.attr.c_muted, 12f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(this, 20);
        params.bottomMargin = Ui.dp(this, 6);
        view.setLayoutParams(params);
        root.addView(view);
        return view;
    }

    /**
     * Adds a note to the form and returns it.
     *
     * This helper adds the view itself: wrap it in addView() and the child already has a parent,
     * which throws IllegalStateException.
     */
    private TextView note(LinearLayout root, String text) {
        TextView view = Ui.label(this, text, R.attr.c_hint_readable, 11f);
        view.setLineSpacing(Ui.dp(this, 3), 1f);
        view.setPadding(0, 0, 0, Ui.dp(this, 4));
        root.addView(view, Ui.matchWrap());
        return view;
    }

    private TextView errorSlot(LinearLayout root) {
        TextView view = Ui.label(this, "", R.attr.c_danger, 12f);
        view.setVisibility(View.GONE);
        root.addView(view, Ui.matchWrap());
        return view;
    }

    private void showError(TextView slot, String message) {
        if (message == null || message.isEmpty()) {
            slot.setVisibility(View.GONE);
            slot.setText("");
            return;
        }
        slot.setText(message);
        slot.setVisibility(View.VISIBLE);
    }

    private EditText input(LinearLayout root, String value) {
        EditText field = new EditText(this);
        field.setTextAppearance(this, R.style.InputField);
        field.setBackgroundResource(R.drawable.bg_input);
        field.setTextColor(Ui.color(this, R.attr.c_text));
        field.setHintTextColor(Ui.color(this, R.attr.c_hint));
        field.setText(value == null ? "" : value);
        field.setSingleLine(true);
        field.setMinHeight(Ui.dp(this, 48));
        field.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        field.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(field, Ui.matchWrap());
        return field;
    }

    private Button chip(String text, boolean accent, View.OnClickListener listener) {
        Button button = new Button(this, null, 0);
        button.setTextAppearance(this, R.style.BtnChip);
        button.setBackgroundResource(R.drawable.bg_btn_chip);
        button.setTextColor(getColor(accent ? R.attr.c_accent : R.attr.c_text));
        button.setAllCaps(false);
        button.setText(text);
        button.setMinHeight(Ui.dp(this, 44));
        button.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(this, 10);
        button.setLayoutParams(params);
        button.setOnClickListener(listener);
        return button;
    }

    /** One row from item_value_row.xml, where style= applies padding and height correctly. */
    private void addValueRow(final LinearLayout container, String value, String hint,
                             final Runnable afterChange) {
        View row = getLayoutInflater().inflate(R.layout.item_value_row, container, false);
        EditText input = row.findViewById(R.id.row_value);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        Button remove = row.findViewById(R.id.row_remove);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                container.removeView((View) view.getParent());
                if (afterChange != null) {
                    afterChange.run();
                }
            }
        });
        container.addView(row);
    }

    /** Reads every row's value, dropping blanks and duplicates. */
    private static List<String> rowsOf(LinearLayout container) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < container.getChildCount(); index++) {
            EditText field = container.getChildAt(index).findViewById(R.id.row_value);
            if (field == null) {
                continue;
            }
            String value = field.getText().toString().trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    // ------------------------------------------------------------- save + rules

    private void save(EditText name, TextView nameError, Spinner kind, EditText baseUrl,
                      TextView urlError, LinearLayout keyRows, TextView keyError,
                      LinearLayout modelRows, TextView modelError,
                      ProviderProfile draft, ProviderProfile existing) {
        // Validate first, and keep the sheet open when something is wrong: the old order
        // (save, close, then complain) left a broken provider in the list and an unseen toast.
        showError(nameError, null);
        showError(urlError, null);
        showError(keyError, null);
        showError(modelError, null);

        String providerName = name.getText().toString().trim();
        String url = baseUrl.getText().toString().trim();
        List<String> keys = rowsOf(keyRows);
        List<String> models = rowsOf(modelRows);
        boolean valid = true;

        if (providerName.isEmpty()) {
            showError(nameError, getString(R.string.provider_name_required));
            valid = false;
        } else if (duplicateName(providerName, existing)) {
            showError(nameError, getString(R.string.provider_dup_name));
            valid = false;
        }
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            showError(urlError, getString(R.string.provider_url_invalid));
            valid = false;
        }
        if (keys.isEmpty()) {
            showError(keyError, getString(R.string.provider_keys_required));
            valid = false;
        }
        if (models.isEmpty()) {
            showError(modelError, getString(R.string.provider_models_required));
            valid = false;
        }
        if (!valid) {
            return;
        }

        draft.name = providerName;
        draft.kind = ProviderProfile.kinds()[kind.getSelectedItemPosition()];
        draft.baseUrl = url;
        draft.apiKeys.clear();
        draft.apiKeys.addAll(keys);
        draft.models.clear();
        draft.models.addAll(models);
        // Editing never silently re-arms a provider the user switched off.
        draft.enabled = existing == null || existing.enabled;

        if (existing == null) {
            providers.add(draft);
        } else {
            int at = providers.indexOf(existing);
            if (at >= 0) {
                providers.set(at, draft);
            }
        }
        persist();
        closeEditor();
        announceSaved(draft);
    }

    private boolean duplicateName(String name, ProviderProfile existing) {
        for (ProviderProfile provider : providers) {
            if (provider == existing) {
                continue;
            }
            if (provider.name.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Says what was saved, where it landed in the fallback order, and whether it is in use. */
    private void announceSaved(ProviderProfile saved) {
        int index = providers.indexOf(saved);
        ProviderProfile active = Providers.primary(providers);
        if (active == saved) {
            Ui.toast(this, getString(R.string.provider_saved_active, saved.name));
        } else {
            Ui.toast(this, getString(R.string.provider_saved, saved.name, index + 1));
        }
        ListView list = findViewById(R.id.provider_list);
        if (index >= 0) {
            list.setSelection(index);
        }
        if (active != null && active != saved) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.provider_not_active, active.name))
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.provider_use_now,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    useAsActive();
                                }
                            })
                    .show();
        }
    }

    /** Makes the top provider's first model the selected one, so "saved" means "in use". */
    private void useAsActive() {
        ProviderProfile active = Providers.primary(providers);
        if (active == null || active.models.isEmpty()) {
            return;
        }
        Config config = store.loadConfig();
        config.model = Providers.encode(active.id, active.models.get(0));
        store.saveConfig(config);
        Ui.toast(this, getString(R.string.provider_now_active, active.name));
    }

    private void confirmDelete(final ProviderProfile provider) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.provider_delete_title, provider.name))
                .setMessage(R.string.provider_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.provider_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        providers.remove(provider);
                        persist();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------- model fetching

    private void fetchModelsInto(int kindIndex, String currentKind, String baseUrl,
                                 LinearLayout keyRows, final LinearLayout modelRows) {
        List<String> keys = rowsOf(keyRows);
        if (keys.isEmpty()) {
            Ui.toast(this, getString(R.string.provider_keys_required));
            return;
        }
        final String kind = ProviderProfile.kinds()[kindIndex];
        final String base = baseUrl == null || baseUrl.trim().isEmpty()
                ? ProviderProfile.defaultBaseUrl(kind) : baseUrl.trim();
        Ui.toast(this, getString(R.string.provider_fetching));

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> found = new ArrayList<>();
                int usedKey = 0;
                for (int index = 0; index < keys.size() && found.isEmpty(); index++) {
                    found = ProviderProbe.fetchModels(kind, base, keys.get(index));
                    usedKey = index + 1;
                }
                final List<String> models = found;
                final int keyIndex = usedKey;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (models.isEmpty()) {
                            Ui.toast(ProvidersActivity.this,
                                    getString(R.string.provider_fetch_failed));
                            return;
                        }
                        Ui.toast(ProvidersActivity.this,
                                getString(R.string.provider_fetch_kept, keyIndex));
                        pickModels(models, modelRows);
                    }
                });
            }
        }, "fetch-models").start();
    }

    /** The picker keeps ids the user typed by hand instead of deleting them silently. */
    private void pickModels(final List<String> found, final LinearLayout modelRows) {
        final String[] labels = found.toArray(new String[0]);
        final boolean[] checked = new boolean[found.size()];
        final List<String> typed = rowsOf(modelRows);
        for (int index = 0; index < found.size(); index++) {
            checked[index] = typed.contains(found.get(index));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.provider_fetch_title, found.size()))
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        modelRows.removeAllViews();
                        // Hand-typed ids that the service did not list are kept, not discarded.
                        for (String keep : typed) {
                            if (!found.contains(keep)) {
                                addValueRow(modelRows, keep,
                                        getString(R.string.provider_model_hint), null);
                            }
                        }
                        for (int index = 0; index < labels.length; index++) {
                            if (checked[index]) {
                                addValueRow(modelRows, labels[index],
                                        getString(R.string.provider_model_hint), null);
                            }
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------- readiness test

    private void runProbe(EditText name, Spinner kind, EditText baseUrl, LinearLayout keyRows,
                          LinearLayout modelRows, final TextView status, final ProgressBar bar,
                          final ProviderProfile existing) {
        if (probeThread != null) {
            Ui.toast(this, getString(R.string.provider_test_running));
            return;
        }
        List<String> keys = rowsOf(keyRows);
        List<String> models = rowsOf(modelRows);
        if (keys.isEmpty() || models.isEmpty()) {
            Ui.toast(this, getString(keys.isEmpty()
                    ? R.string.provider_keys_required : R.string.provider_models_required));
            return;
        }
        final String kindValue = ProviderProfile.kinds()[kind.getSelectedItemPosition()];
        String rawUrl = baseUrl.getText().toString().trim();
        final String base = rawUrl.isEmpty() ? ProviderProfile.defaultBaseUrl(kindValue) : rawUrl;
        final String providerName = name.getText().toString().trim();
        final List<String> finalKeys = keys;
        final List<String> finalModels = models;

        bar.setVisibility(View.VISIBLE);
        bar.setMax(finalModels.size());
        bar.setProgress(0);
        status.setText(getString(R.string.provider_test_running));

        probeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> lines = new ArrayList<>();
                int ready = 0;
                for (int index = 0; index < finalModels.size(); index++) {
                    final String model = finalModels.get(index);
                    final int position = index;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bar.setProgress(position);
                            status.setText(getString(R.string.provider_test_working,
                                    position + 1, finalModels.size(), model));
                        }
                    });
                    ProviderProbe.Ping result = null;
                    int keyIndex = 0;
                    for (int k = 0; k < finalKeys.size(); k++) {
                        result = ProviderProbe.ping(kindValue, base, finalKeys.get(k), model);
                        keyIndex = k + 1;
                        if (result.ok) {
                            break;
                        }
                    }
                    if (result != null && result.ok) {
                        ready++;
                        lines.add(getString(R.string.provider_test_ok, model, result.millis, keyIndex));
                    } else {
                        lines.add(getString(R.string.provider_test_fail, model,
                                result == null ? "?" : result.detail));
                    }
                }
                final int readyCount = ready;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        probeThread = null;
                        bar.setProgress(finalModels.size());
                        bar.setVisibility(View.GONE);
                        // The verdict is stored, so the list badge can mean "proven", not "filled".
                        if (existing != null) {
                            ProviderProfile live = Providers.byId(providers, existing.id);
                            if (live != null) {
                                live.lastProbeAt = System.currentTimeMillis();
                                live.lastProbeReady = readyCount;
                                live.lastProbeTotal = finalModels.size();
                                persist();
                            }
                        }
                        status.setText(getString(R.string.provider_test_summary,
                                readyCount, finalModels.size()));
                        showProbeReport(providerName, readyCount, finalModels.size(), lines);
                    }
                });
            }
        }, "provider-readiness");
        probeThread.start();
    }

    private void showProbeReport(String providerName, int ready, int total, List<String> lines) {
        LinearLayout holder = Ui.column(this);
        int pad = Ui.dp(this, 18);
        holder.setPadding(pad, pad, pad, pad);
        TextView body = Ui.label(this,
                getString(R.string.provider_test_summary, ready, total) + "\n\n" + join(lines),
                R.attr.c_text, 13f);
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        body.setTextDirection(View.TEXT_DIRECTION_RTL);
        holder.addView(body, Ui.matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(holder);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6f)));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.provider_test_title,
                        providerName.isEmpty() ? getString(R.string.menu_providers) : providerName))
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(value);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- adapter

    private final class ProviderAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return providers.size();
        }

        @Override
        public Object getItem(int position) {
            return providers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return providers.get(position).id.hashCode();
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View row = recycled;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_provider, parent, false);
            }
            final ProviderProfile provider = providers.get(position);

            TextView order = row.findViewById(R.id.row_provider_order);
            TextView name = row.findViewById(R.id.row_provider_name);
            TextView status = row.findViewById(R.id.row_provider_status);
            TextView kindView = row.findViewById(R.id.row_provider_kind);
            TextView detail = row.findViewById(R.id.row_provider_detail);
            TextView url = row.findViewById(R.id.row_provider_url);
            Button edit = row.findViewById(R.id.row_provider_edit);
            Button test = row.findViewById(R.id.row_provider_test);
            Button toggle = row.findViewById(R.id.row_provider_toggle);

            order.setText(Ui.formatInt(position + 1));
            name.setText(provider.name);
            kindView.setText(ProviderProfile.kindLabel(provider.kind));
            detail.setText(getString(R.string.provider_detail,
                    provider.keyCount(), provider.models.size()));
            url.setText(provider.normalizedBaseUrl());

            // Green must mean "a real request succeeded", never merely "the fields are filled".
            if (!provider.enabled) {
                status.setText(R.string.provider_state_off);
                status.setBackgroundResource(R.drawable.bg_badge_neutral);
                status.setTextColor(Ui.color(ProvidersActivity.this, R.attr.c_muted));
            } else if (provider.lastProbeAt == 0) {
                status.setText(R.string.provider_state_untested);
                status.setBackgroundResource(R.drawable.bg_badge_neutral);
                status.setTextColor(Ui.color(ProvidersActivity.this, R.attr.c_muted));
            } else if (provider.lastProbeReady == 0) {
                status.setText(R.string.provider_state_broken);
                status.setBackgroundResource(R.drawable.bg_badge_danger);
                status.setTextColor(Ui.color(ProvidersActivity.this, R.attr.c_danger));
            } else if (provider.lastProbeReady < provider.lastProbeTotal) {
                status.setText(getString(R.string.provider_state_partial,
                        provider.lastProbeReady, provider.lastProbeTotal));
                status.setBackgroundResource(R.drawable.bg_badge_danger);
                status.setTextColor(Ui.color(ProvidersActivity.this, R.attr.c_danger));
            } else {
                status.setText(R.string.provider_state_verified);
                status.setBackgroundResource(R.drawable.bg_badge_success);
                status.setTextColor(Ui.color(ProvidersActivity.this, R.attr.c_success));
            }

            test.setText(R.string.provider_test_all);
            toggle.setText(provider.enabled ? R.string.provider_disable : R.string.provider_enable);

            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editProvider(provider);
                }
            });
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    probeSavedProvider(provider);
                }
            });
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    provider.enabled = !provider.enabled;
                    persist();
                }
            });
            return row;
        }
    }

    /** Readiness test for a provider straight from the list. */
    private void probeSavedProvider(final ProviderProfile provider) {
        if (provider.apiKeys.isEmpty() || provider.models.isEmpty()) {
            Ui.toast(this, getString(provider.apiKeys.isEmpty()
                    ? R.string.provider_keys_required : R.string.provider_models_required));
            return;
        }
        if (probeThread != null) {
            Ui.toast(this, getString(R.string.provider_test_running));
            return;
        }
        Ui.toast(this, getString(R.string.provider_testing));
        final String kind = provider.kind;
        final String base = provider.normalizedBaseUrl();
        final List<String> keys = new ArrayList<>(provider.apiKeys);
        final List<String> models = new ArrayList<>(provider.models);
        probeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> lines = new ArrayList<>();
                int ready = 0;
                for (String model : models) {
                    ProviderProbe.Ping result = null;
                    int keyIndex = 0;
                    for (int index = 0; index < keys.size(); index++) {
                        result = ProviderProbe.ping(kind, base, keys.get(index), model);
                        keyIndex = index + 1;
                        if (result.ok) {
                            break;
                        }
                    }
                    if (result != null && result.ok) {
                        ready++;
                        lines.add(getString(R.string.provider_test_ok, model, result.millis, keyIndex));
                    } else {
                        lines.add(getString(R.string.provider_test_fail, model,
                                result == null ? "?" : result.detail));
                    }
                }
                final int readyCount = ready;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        probeThread = null;
                        ProviderProfile live = Providers.byId(providers, provider.id);
                        if (live != null) {
                            live.lastProbeAt = System.currentTimeMillis();
                            live.lastProbeReady = readyCount;
                            live.lastProbeTotal = models.size();
                            persist();
                        }
                        showProbeReport(provider.name, readyCount, models.size(), lines);
                    }
                });
            }
        }, "provider-readiness");
        probeThread.start();
    }
}

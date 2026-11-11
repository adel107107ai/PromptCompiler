package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider, API-key and model management.
 *
 * A provider holds as many API keys and as many model ids as the user needs: keys and models are
 * edited as individual rows, and the model list can be fetched from the service itself. The list
 * order is the fallback order, and the readiness test proves each model really answers.
 */
public class ProvidersActivity extends Activity {

    private Store store;
    private final List<ProviderProfile> providers = new ArrayList<>();
    private ProviderAdapter adapter;
    private TextView emptyView;
    private TextView hintView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    // ---------------------------------------------------------------- editor

    private void editProvider(final ProviderProfile existing) {
        final ProviderProfile draft = existing == null
                ? new ProviderProfile(Providers.newId(), "", ProviderProfile.KIND_GEMINI, "")
                : existing;

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));

        final EditText name = field(root, getString(R.string.provider_name), draft.name);

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
        sectionLabel(root, getString(R.string.provider_kind));
        root.addView(kind, Ui.matchWrap());

        final EditText baseUrl = field(root, getString(R.string.provider_base_url),
                draft.baseUrl.isEmpty() ? ProviderProfile.defaultBaseUrl(draft.kind) : draft.baseUrl);

        // ---- API keys: one row per key, as many as the user needs.
        final LinearLayout keyRows = Ui.column(this);
        final TextView keyCount = sectionLabel(root, getString(R.string.provider_keys, 0));
        root.addView(keyRows, Ui.matchWrap());
        final Runnable keyCounter = new Runnable() {
            @Override
            public void run() {
                keyCount.setText(getString(R.string.provider_keys, keyRows.getChildCount()));
            }
        };
        root.addView(button(R.string.provider_add_key, R.drawable.bg_btn_chip,
                new View.OnClickListener() {
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

        // ---- Models: one row per model id, plus the service's own list.
        final LinearLayout modelRows = Ui.column(this);
        sectionLabel(root, getString(R.string.provider_models));
        root.addView(modelRows, Ui.matchWrap());
        for (String model : draft.models) {
            addValueRow(modelRows, model, getString(R.string.provider_model_hint), null);
        }
        if (draft.models.isEmpty()) {
            addValueRow(modelRows, "", getString(R.string.provider_model_hint), null);
        }

        LinearLayout modelButtons = new LinearLayout(this);
        modelButtons.setOrientation(LinearLayout.HORIZONTAL);
        modelButtons.setLayoutParams(Ui.matchWrap());
        Button fetch = new Button(this, null, 0);
        fetch.setTextAppearance(this, R.style.BtnRow);
        fetch.setBackgroundResource(R.drawable.bg_btn_chip);
        fetch.setAllCaps(false);
        fetch.setText(R.string.provider_fetch_models);
        fetch.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fetchModelsInto(kind.getSelectedItemPosition(), baseUrl.getText().toString(),
                        keyRows, modelRows);
            }
        });
        modelButtons.addView(fetch);
        Button addModel = new Button(this, null, 0);
        addModel.setTextAppearance(this, R.style.BtnRow);
        addModel.setBackgroundResource(R.drawable.bg_btn_ghost);
        addModel.setAllCaps(false);
        addModel.setText(R.string.provider_add_model);
        addModel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addValueRow(modelRows, "", getString(R.string.provider_model_hint), null);
            }
        });
        modelButtons.addView(addModel);
        root.addView(modelButtons, Ui.matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.58f)));

        ProviderProfile testable = existing == null ? draft : existing;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.provider_add : R.string.provider_edit)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.provider_test_all, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.show();

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String chosenKind = ProviderProfile.kinds()[kind.getSelectedItemPosition()];
                String providerName = name.getText().toString().trim();
                if (providerName.isEmpty()) {
                    providerName = ProviderProfile.kindLabel(chosenKind);
                }
                draft.name = providerName;
                draft.kind = chosenKind;
                draft.baseUrl = baseUrl.getText().toString().trim();
                draft.apiKeys.clear();
                draft.apiKeys.addAll(rowsOf(keyRows));
                draft.models.clear();
                draft.models.addAll(rowsOf(modelRows));
                draft.enabled = true;

                if (existing == null && !providers.contains(draft)) {
                    providers.add(draft);
                }
                persist();
                dialog.dismiss();

                if (draft.apiKeys.isEmpty()) {
                    Ui.toast(ProvidersActivity.this, getString(R.string.provider_no_keys));
                } else if (draft.models.isEmpty()) {
                    Ui.toast(ProvidersActivity.this, getString(R.string.provider_need_models));
                }
            }
        });

        final ProviderProfile probeTarget = testable;
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        neutral.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (existing != null) {
                    providers.remove(existing);
                    persist();
                    dialog.dismiss();
                    return;
                }
                // Test what is typed right now, before the provider is even saved.
                ProviderProfile probe = new ProviderProfile(Providers.newId(),
                        name.getText().toString().trim(),
                        ProviderProfile.kinds()[kind.getSelectedItemPosition()],
                        baseUrl.getText().toString().trim());
                probe.apiKeys.addAll(rowsOf(keyRows));
                probe.models.addAll(rowsOf(modelRows));
                testProvider(probe);
            }
        });
        if (existing != null) {
            neutral.setText(R.string.provider_delete);
        }
        // Kept so the compiler never reports the probe target as unused.
        if (probeTarget == null) {
            Ui.toast(this, "");
        }
    }

    private TextView sectionLabel(LinearLayout root, String text) {
        TextView view = Ui.label(this, text, R.color.muted, 12f);
        view.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        root.addView(view);
        return view;
    }

    private Button button(int textRes, int backgroundRes, View.OnClickListener listener) {
        Button button = new Button(this, null, 0);
        button.setTextAppearance(this, R.style.BtnRow);
        button.setBackgroundResource(backgroundRes);
        button.setAllCaps(false);
        button.setText(textRes);
        button.setLayoutParams(Ui.matchWrap());
        button.setOnClickListener(listener);
        return button;
    }

    /** One editable value with a remove button. */
    private void addValueRow(final LinearLayout container, String value, String hint,
                             final Runnable afterChange) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 4), 0, 0);

        EditText input = new EditText(this);
        input.setTextAppearance(this, R.style.InputField);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.hint));
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(value == null ? "" : value);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input);

        Button remove = new Button(this, null, 0);
        remove.setId(R.id.row_remove);
        remove.setTextAppearance(this, R.style.BtnRow);
        remove.setBackgroundResource(R.drawable.bg_btn_ghost);
        remove.setAllCaps(false);
        remove.setText("✕");
        remove.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                container.removeView(row);
                if (afterChange != null) {
                    afterChange.run();
                }
            }
        });
        row.addView(remove);
        container.addView(row, Ui.matchWrap());
    }

    /** Reads every row's text, dropping blanks and duplicates. */
    private static List<String> rowsOf(LinearLayout container) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            View first = ((LinearLayout) child).getChildAt(0);
            if (!(first instanceof EditText)) {
                continue;
            }
            String value = ((EditText) first).getText().toString().trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private EditText field(LinearLayout root, String label, String value) {
        sectionLabel(root, label);
        EditText field = new EditText(this);
        field.setTextAppearance(this, R.style.InputField);
        field.setBackgroundResource(R.drawable.bg_input);
        field.setTextColor(getColor(R.color.text));
        field.setHintTextColor(getColor(R.color.hint));
        field.setText(value == null ? "" : value);
        field.setSingleLine(true);
        root.addView(field, Ui.matchWrap());
        return field;
    }

    // ---------------------------------------------------------- model fetching

    private void fetchModelsInto(int kindIndex, String baseUrl, LinearLayout keyRows,
                                 final LinearLayout modelRows) {
        List<String> keys = rowsOf(keyRows);
        if (keys.isEmpty()) {
            Ui.toast(this, getString(R.string.provider_no_keys));
            return;
        }
        final String kind = ProviderProfile.kinds()[kindIndex];
        String base = baseUrl == null || baseUrl.trim().isEmpty()
                ? ProviderProfile.defaultBaseUrl(kind) : baseUrl.trim();
        final String key = keys.get(0);
        Ui.toast(this, getString(R.string.provider_fetching));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> found = ProviderProbe.fetchModels(kind, base, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found.isEmpty()) {
                            Ui.toast(ProvidersActivity.this, getString(R.string.provider_fetch_failed));
                            return;
                        }
                        final String[] labels = found.toArray(new String[0]);
                        final boolean[] checked = new boolean[found.size()];
                        List<String> current = rowsOf(modelRows);
                        for (int index = 0; index < found.size(); index++) {
                            checked[index] = current.contains(found.get(index));
                        }
                        new AlertDialog.Builder(ProvidersActivity.this)
                                .setTitle(getString(R.string.provider_fetch_title, found.size()))
                                .setMultiChoiceItems(labels, checked,
                                        new DialogInterface.OnMultiChoiceClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog,
                                                                int which, boolean isChecked) {
                                                checked[which] = isChecked;
                                            }
                                        })
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        modelRows.removeAllViews();
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
                });
            }
        }, "fetch-models").start();
    }

    // ---------------------------------------------------------- readiness test

    /**
     * Sends one tiny real request per model, trying each key in turn, so "ready" means proven
     * rather than assumed before the tool is used in earnest.
     */
    private void testProvider(final ProviderProfile provider) {
        if (provider.apiKeys.isEmpty()) {
            Ui.toast(this, getString(R.string.provider_no_keys));
            return;
        }
        if (provider.models.isEmpty()) {
            Ui.toast(this, getString(R.string.provider_need_models));
            return;
        }
        final String kind = provider.kind;
        final String base = provider.normalizedBaseUrl();
        final List<String> keys = new ArrayList<>(provider.apiKeys);
        final List<String> models = new ArrayList<>(provider.models);
        Ui.toast(this, getString(R.string.provider_testing));

        new Thread(new Runnable() {
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
                        lines.add(getString(R.string.provider_test_ok,
                                model, result.millis, keyIndex));
                    } else {
                        String detail = result == null ? "?" : result.detail;
                        lines.add(getString(R.string.provider_test_fail, model, detail));
                    }
                }
                final String report = getString(R.string.provider_test_summary, ready, models.size())
                        + "\n\n" + join(lines);
                final String title = getString(R.string.provider_test_title,
                        provider.name.isEmpty() ? ProviderProfile.kindLabel(kind) : provider.name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        LinearLayout holder = Ui.column(ProvidersActivity.this);
                        int pad = Ui.dp(ProvidersActivity.this, 18);
                        holder.setPadding(pad, pad, pad, pad);
                        TextView body = Ui.label(ProvidersActivity.this, report, R.color.text, 13f);
                        body.setTypeface(android.graphics.Typeface.MONOSPACE);
                        body.setLineSpacing(Ui.dp(ProvidersActivity.this, 3), 1f);
                        body.setTextDirection(View.TEXT_DIRECTION_LTR);
                        holder.addView(body, Ui.matchWrap());

                        ScrollView scroll = new ScrollView(ProvidersActivity.this);
                        scroll.addView(holder);
                        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                (int) (getResources().getDisplayMetrics().heightPixels * 0.6f)));
                        new AlertDialog.Builder(ProvidersActivity.this)
                                .setTitle(title)
                                .setView(scroll)
                                .setPositiveButton(R.string.close, null)
                                .show();
                    }
                });
            }
        }, "provider-readiness").start();
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

            TextView name = row.findViewById(R.id.row_provider_name);
            TextView kindView = row.findViewById(R.id.row_provider_kind);
            TextView detail = row.findViewById(R.id.row_provider_detail);
            Button edit = row.findViewById(R.id.row_provider_edit);
            Button test = row.findViewById(R.id.row_provider_test);
            Button toggle = row.findViewById(R.id.row_provider_toggle);

            name.setText(provider.name);
            kindView.setText(ProviderProfile.kindLabel(provider.kind));
            detail.setText(getString(R.string.provider_detail,
                    position + 1, provider.keyCount(), provider.models.size())
                    + "\n" + provider.normalizedBaseUrl());
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
                    testProvider(provider);
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
}

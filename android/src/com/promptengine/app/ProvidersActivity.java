package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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
 * Provider and API-key management.
 *
 * Editing a provider also sets its place in the fallback order: providers are tried top to bottom,
 * and inside one provider each API key is tried with each model.
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

    // ---------------------------------------------------------------- editing

    private void editProvider(final ProviderProfile existing) {
        final ProviderProfile draft = existing == null
                ? new ProviderProfile(Providers.newId(), "", ProviderProfile.KIND_GEMINI, "")
                : existing;

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 8), pad, 0);

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
        label(root, getString(R.string.provider_kind));
        root.addView(kind, Ui.matchWrap());

        final EditText baseUrl = field(root, getString(R.string.provider_base_url),
                draft.baseUrl.isEmpty() ? ProviderProfile.defaultBaseUrl(draft.kind) : draft.baseUrl);

        final EditText keys = multiField(root, getString(R.string.provider_keys),
                getString(R.string.provider_keys_hint), join(draft.apiKeys));

        final EditText models = multiField(root, getString(R.string.provider_models),
                getString(R.string.provider_models_hint), join(draft.models));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.55f)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.provider_add : R.string.provider_edit)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String chosenKind = ProviderProfile.kinds()[kind.getSelectedItemPosition()];
                        String providerName = name.getText().toString().trim();
                        String url = baseUrl.getText().toString().trim();
                        List<String> keyList = split(keys.getText().toString());
                        List<String> modelList = split(models.getText().toString());

                        if (providerName.isEmpty()) {
                            providerName = ProviderProfile.kindLabel(chosenKind);
                        }
                        if (modelList.isEmpty()) {
                            Ui.toast(ProvidersActivity.this, getString(R.string.provider_need_models));
                        }
                        draft.name = providerName;
                        draft.kind = chosenKind;
                        draft.baseUrl = url;
                        draft.apiKeys.clear();
                        draft.apiKeys.addAll(keyList);
                        draft.models.clear();
                        draft.models.addAll(modelList);
                        draft.enabled = true;

                        if (existing == null) {
                            providers.add(draft);
                        }
                        persist();
                    }
                });

        if (existing != null) {
            builder.setNeutralButton(R.string.provider_delete, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    providers.remove(existing);
                    persist();
                }
            });
        }
        builder.show();
    }

    private EditText field(LinearLayout root, String label, String value) {
        label(root, label);
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

    private EditText multiField(LinearLayout root, String label, String hint, String value) {
        label(root, label);
        EditText field = new EditText(this);
        field.setTextAppearance(this, R.style.InputField);
        field.setBackgroundResource(R.drawable.bg_input);
        field.setTextColor(getColor(R.color.text));
        field.setHintTextColor(getColor(R.color.hint));
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setMinLines(3);
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        root.addView(field, Ui.matchWrap());
        return field;
    }

    private void label(LinearLayout root, String text) {
        TextView view = Ui.label(this, text, R.color.muted, 12f);
        view.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        root.addView(view);
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

    /** One entry per line, blanks dropped, duplicates removed. */
    private static List<String> split(String text) {
        List<String> values = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    // ----------------------------------------------------------------- testing

    private void testProvider(final ProviderProfile provider) {
        if (provider.apiKeys.isEmpty()) {
            Ui.toast(this, getString(R.string.provider_no_keys));
            return;
        }
        if (ProviderProfile.KIND_ANTHROPIC.equals(provider.kind)) {
            Ui.toast(this, getString(R.string.provider_test_unsupported));
            return;
        }
        Ui.toast(this, getString(R.string.provider_testing));

        final String kind = provider.kind;
        final String base = provider.normalizedBaseUrl();
        final String key = provider.apiKeys.get(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = ProviderProbe.listModels(kind, base, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(ProvidersActivity.this)
                                .setTitle(provider.name)
                                .setMessage(result)
                                .setPositiveButton(R.string.close, null)
                                .show();
                    }
                });
            }
        }, "provider-test").start();
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

package com.promptengine.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Prompt override, the model picker, and the way into provider and key management. */
public class SettingsActivity extends Activity {

    private Store store;
    /** The look this activity was built with; a change means it must be rebuilt. */
    private String appliedLook = ThemePreset.DEFAULT;
    private Config config;

    private EditText systemPromptField;
    private TextView providerSummary;
    private TextView modelLimits;
    private Spinner modelSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        appliedLook = ThemePreset.apply(this);
        setContentView(R.layout.activity_settings);

        store = new Store(this);
        config = store.loadConfig();

        systemPromptField = findViewById(R.id.system_prompt);
        providerSummary = findViewById(R.id.provider_summary);
        modelLimits = findViewById(R.id.model_limits);
        modelSpinner = findViewById(R.id.model_spinner);

        systemPromptField.setText(config.systemPromptOverride);

        findViewById(R.id.btn_manage_providers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, ProvidersActivity.class));
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Config draft = new Config();
                draft.apiKey = config.apiKey;
                draft.baseUrl = config.baseUrl;
                draft.model = config.model;
                draft.systemPromptOverride = systemPromptField.getText().toString();
                store.saveConfig(draft);
                config = store.loadConfig();
                Ui.toast(SettingsActivity.this, getString(R.string.saved));
                finish();
            }
        });

        buildLookPicker();
        loadModelPicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadModelPicker();
    }

    /** Three tappable rows; the active one is filled, so the current look is obvious. */
    private void buildLookPicker() {
        LinearLayout holder = findViewById(R.id.look_options);
        holder.removeAllViews();
        final String current = store.loadLook();
        for (final String look : ThemePreset.all()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            row.setBackgroundResource(look.equals(current)
                    ? R.drawable.bg_option_primary : R.drawable.bg_option);
            LinearLayout.LayoutParams params = Ui.matchWrap();
            params.topMargin = Ui.dp(this, 8);
            row.setLayoutParams(params);
            row.setMinimumHeight(Ui.dp(this, 56));

            TextView name = Ui.label(this, getString(ThemePreset.nameFor(look)),
                    look.equals(current) ? R.attr.c_on_accent : R.attr.c_text, 15f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(name);
            TextView desc = Ui.label(this, getString(ThemePreset.descriptionFor(look)),
                    look.equals(current) ? R.attr.c_on_accent : R.attr.c_muted, 12f);
            row.addView(desc);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    store.saveLook(look);
                    Ui.toast(SettingsActivity.this, getString(R.string.look_applied,
                            getString(ThemePreset.nameFor(look))));
                    // Only a rebuild repaints an activity, so both this screen and the main
                    // screen pick the new look up immediately.
                    recreate();
                }
            });
            holder.addView(row);
        }
    }

    private void loadModelPicker() {
        List<ProviderProfile> providers = Providers.load(store, config.apiKey);
        List<Providers.ModelChoice> choices = Providers.choices(providers);

        List<String> names = new ArrayList<>();
        int selected = -1;
        for (int index = 0; index < choices.size(); index++) {
            names.add(choices.get(index).label());
            if (choices.get(index).selection().equals(config.model)) {
                selected = index;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        if (selected >= 0) {
            modelSpinner.setSelection(selected);
        }
        final List<Providers.ModelChoice> finalChoices = choices;
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= finalChoices.size()) {
                    return;
                }
                Providers.ModelChoice choice = finalChoices.get(position);
                config.model = choice.selection();
                store.saveConfig(config);
                showLimits(choice.model);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (selected >= 0) {
            showLimits(choices.get(selected).model);
        }

        int enabled = 0;
        int keys = 0;
        int models = 0;
        for (ProviderProfile provider : providers) {
            if (provider.enabled) {
                enabled++;
            }
            keys += provider.keyCount();
            models += provider.models.size();
        }
        providerSummary.setText(getString(R.string.settings_provider_summary,
                enabled, providers.size(), keys, models));
    }

    private void showLimits(String modelId) {
        ModelInfo model = ModelCatalog.find(modelId);
        StringBuilder text = new StringBuilder(getString(R.string.label_rate_limits)).append(": ");
        text.append("RPM ").append(model == null || model.rpm == null ? "—" : String.valueOf(model.rpm)).append("  ·  ");
        text.append("TPM ").append(model == null || model.tpm == null ? "—" : Ui.formatTokens(model.tpm)).append("  ·  ");
        text.append("RPD ").append(model == null || model.rpd == null ? "—" : String.valueOf(model.rpd));
        text.append("\n").append(getString(R.string.settings_auto_switch));
        modelLimits.setText(text.toString());
    }
}

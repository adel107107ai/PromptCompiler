package com.promptengine.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Prompt override, the model picker, and the way into provider and key management. */
public class SettingsActivity extends Activity {

    private Store store;
    private Config config;

    private EditText systemPromptField;
    private TextView providerSummary;
    private TextView modelLimits;
    private Spinner modelSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
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

        loadModelPicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadModelPicker();
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

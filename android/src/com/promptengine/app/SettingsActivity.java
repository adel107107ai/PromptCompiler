package com.promptengine.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** API key, model, prompt override and API base URL. */
public class SettingsActivity extends Activity {

    private Store store;
    private Config config;
    private List<ModelInfo> models;

    private EditText apiKeyField;
    private EditText modelIdField;
    private EditText systemPromptField;
    private EditText baseUrlField;
    private TextView limitsView;
    private TextView verifyStatus;
    private Button toggleKeyButton;
    private Spinner modelSpinner;
    private boolean keyVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        store = new Store(this);
        config = store.loadConfig();
        models = new ArrayList<>();
        for (ModelInfo model : ModelCatalog.all(store)) {
            if (model.generate) {
                models.add(model);
            }
        }

        apiKeyField = findViewById(R.id.api_key);
        modelIdField = findViewById(R.id.model_id);
        systemPromptField = findViewById(R.id.system_prompt);
        baseUrlField = findViewById(R.id.base_url);
        limitsView = findViewById(R.id.model_limits);
        verifyStatus = findViewById(R.id.verify_status);
        toggleKeyButton = findViewById(R.id.btn_toggle_key);
        modelSpinner = findViewById(R.id.model_spinner);

        apiKeyField.setText(config.apiKey);
        apiKeyField.setHint(Store.mask("AIza0000000000000000"));
        modelIdField.setText(config.model);
        systemPromptField.setText(config.systemPromptOverride);
        baseUrlField.setText(config.baseUrl);

        List<String> names = new ArrayList<>();
        int selected = -1;
        for (int index = 0; index < models.size(); index++) {
            names.add(models.get(index).name);
            if (models.get(index).id.equals(config.model)) {
                selected = index;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        if (selected >= 0) {
            modelSpinner.setSelection(selected);
            showLimits(models.get(selected));
        }
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelInfo model = models.get(position);
                // The text field stays the single source of truth; the spinner just fills it in.
                modelIdField.setText(model.id);
                showLimits(model);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        toggleKeyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                keyVisible = !keyVisible;
                apiKeyField.setInputType(keyVisible
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                apiKeyField.setSelection(apiKeyField.getText().length());
                toggleKeyButton.setText(keyVisible ? R.string.btn_hide_key : R.string.btn_toggle_key);
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });

        findViewById(R.id.btn_verify).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                verify();
            }
        });
    }

    private void showLimits(ModelInfo model) {
        StringBuilder text = new StringBuilder(getString(R.string.label_rate_limits)).append(": ");
        text.append("RPM ").append(model.rpm == null ? "—" : String.valueOf(model.rpm)).append("  ·  ");
        text.append("TPM ").append(model.tpm == null ? "—" : Ui.formatTokens(model.tpm)).append("  ·  ");
        text.append("RPD ").append(model.rpd == null ? "—" : String.valueOf(model.rpd));
        limitsView.setText(text.toString());
    }

    private Config readFields() {
        Config draft = new Config();
        draft.apiKey = apiKeyField.getText().toString().trim();
        String model = modelIdField.getText().toString().trim();
        draft.model = model.isEmpty() ? ModelCatalog.DEFAULT_MODEL : model;
        draft.systemPromptOverride = systemPromptField.getText().toString();
        draft.baseUrl = baseUrlField.getText().toString().trim();
        return draft;
    }

    private void save() {
        Config draft = readFields();
        store.saveConfig(draft);
        config = draft;
        Ui.toast(this, getString(R.string.saved));
        finish();
    }

    private void verify() {
        final Config draft = readFields();
        if (draft.apiKey.isEmpty()) {
            Ui.toast(this, getString(R.string.err_need_key));
            return;
        }
        verifyStatus.setText(R.string.status_connecting);
        GeminiClient.listModels(draft, new GeminiClient.ModelsCallback() {
            @Override
            public void onModels(List<ModelInfo> live) {
                store.saveLiveModels(live);
                StringBuilder text = new StringBuilder(
                        getString(R.string.verify_ok, live.size()));
                boolean found = false;
                for (ModelInfo model : live) {
                    if (model.id.equals(draft.model)) {
                        found = true;
                        break;
                    }
                }
                text.append("\n\n").append(found
                        ? getString(R.string.verify_model_ok, draft.model)
                        : getString(R.string.verify_model_missing, draft.model));
                verifyStatus.setText(text.toString());
            }

            @Override
            public void onFailure(int kind, String detail) {
                String message;
                if (kind == GeminiClient.ERR_KEY) {
                    message = getString(R.string.err_need_key);
                } else if (kind == GeminiClient.ERR_BLOCKED) {
                    message = getString(R.string.err_blocked);
                } else if (kind == GeminiClient.ERR_OFFLINE) {
                    message = getString(R.string.err_offline);
                } else {
                    message = getString(R.string.err_http);
                }
                if (detail != null && !detail.trim().isEmpty()) {
                    message = message + "\n\n" + detail;
                }
                verifyStatus.setText(message);
            }
        });
    }
}

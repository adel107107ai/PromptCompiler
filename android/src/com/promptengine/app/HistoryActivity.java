package com.promptengine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The request memory: every compiled request is kept here and can be searched, recalled into the
 * main screen for editing, copied, or deleted.
 */
public class HistoryActivity extends Activity {

    private Store store;
    private EditText searchField;
    private TextView countView;
    private TextView emptyView;
    private ListView listView;

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private EntryAdapter adapter;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.forLanguageTag("fa-IR"));

    /** A stored request plus a lowercase haystack, so typing does not rescan the raw JSON. */
    private static final class Entry {
        final JSONObject json;
        final String haystack;

        Entry(JSONObject json) {
            this.json = json;
            this.haystack = (
                    json.optString("input", "") + "\n"
                            + json.optString("raw", "") + "\n"
                            + json.optString("cat", "") + "\n"
                            + json.optString("purpose", "") + "\n"
                            + json.optString("model", "")).toLowerCase();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        setContentView(R.layout.activity_history);

        store = new Store(this);
        searchField = findViewById(R.id.search);
        countView = findViewById(R.id.count);
        emptyView = findViewById(R.id.empty);
        listView = findViewById(R.id.list);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        // Tapping anywhere on a row recalls it, even though the row holds buttons.
        listView.setItemsCanFocus(false);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                open(visible.get(position));
            }
        });

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                applyFilter(editable == null ? "" : editable.toString());
            }
        });

        findViewById(R.id.btn_clear_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmClearAll();
            }
        });

        findViewById(R.id.btn_new_from_memory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                store.setPendingNewRequest();
                finish();
            }
        });

        loadAsync();
    }

    // ----------------------------------------------------------------- load

    private void loadAsync() {
        countView.setText(R.string.status_connecting);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray stored = store.loadHistory();
                final List<Entry> loaded = new ArrayList<>();
                for (int index = 0; index < stored.length(); index++) {
                    JSONObject item = stored.optJSONObject(index);
                    if (item != null) {
                        loaded.add(new Entry(item));
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(loaded);
                        applyFilter(searchField.getText().toString());
                    }
                });
            }
        }, "history-load").start();
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        visible.clear();
        for (Entry entry : all) {
            if (needle.isEmpty() || entry.haystack.contains(needle)) {
                visible.add(entry);
            }
        }
        adapter.notifyDataSetChanged();

        countView.setText(getString(R.string.memory_count, all.size()));
        if (all.isEmpty()) {
            emptyView.setText(R.string.memory_empty);
            emptyView.setVisibility(View.VISIBLE);
        } else if (visible.isEmpty()) {
            emptyView.setText(R.string.memory_no_results);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    // --------------------------------------------------------------- actions

    /**
     * Hands the request to the main screen for editing. The hand-off goes through Store and is
     * consumed in MainActivity.onResume(), so it cannot be lost the way an activity result can.
     */
    private void open(Entry entry) {
        store.setPendingRecall(entry.json.optLong("t", 0));
        finish();
    }

    /**
     * Copies one of the two prompts straight out of the memory, so a stored request can be
     * reused without opening it for editing first.
     */
    private void copyPrompt(Entry entry, boolean persian) {
        String raw = entry.json.optString("raw", "");
        String text = PromptEngine.partialField(raw, persian ? "persian_prompt" : "english_prompt");
        if (text == null && !persian) {
            // A non-JSON answer carries the prompt as its whole body, which is what parse() falls back to.
            String fallback = PromptEngine.parse(raw).englishPrompt;
            text = PromptEngine.EMPTY_PROMPT.equals(fallback) ? null : fallback;
        }
        if (Ui.copy(this, text)) {
            Ui.toast(this, getString(persian
                    ? R.string.memory_copied_persian
                    : R.string.memory_copied_english));
        } else {
            Ui.toast(this, getString(R.string.memory_copy_empty));
        }
    }

    private void confirmDelete(final Entry entry) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.memory_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        delete(entry);
                    }
                })
                .show();
    }

    private void delete(Entry entry) {
        final long timestamp = entry.json.optLong("t", 0);
        final String input = entry.json.optString("input", "");
        all.remove(entry);
        applyFilter(searchField.getText().toString());
        Ui.toast(this, getString(R.string.memory_deleted));
        new Thread(new Runnable() {
            @Override
            public void run() {
                store.deleteHistory(timestamp, input);
            }
        }, "history-delete").start();
    }

    private void confirmClearAll() {
        if (all.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.memory_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        all.clear();
                        applyFilter(searchField.getText().toString());
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                store.clearHistory();
                            }
                        }, "history-clear").start();
                    }
                })
                .show();
    }

    // --------------------------------------------------------------- adapter

    private final class EntryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return visible.get(position).json.optLong("t", position);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View row = recycled;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            }
            final Entry entry = visible.get(position);
            JSONObject json = entry.json;

            TextView date = row.findViewById(R.id.row_date);
            TextView category = row.findViewById(R.id.row_category);
            TextView input = row.findViewById(R.id.row_input);
            TextView model = row.findViewById(R.id.row_model);
            Button delete = row.findViewById(R.id.row_delete);
            Button edit = row.findViewById(R.id.row_edit);
            Button copyPersian = row.findViewById(R.id.row_copy_persian);
            Button copyEnglish = row.findViewById(R.id.row_copy_english);

            date.setText(dateFormat.format(new Date(json.optLong("t", 0))));
            category.setText(ModelCatalog.categoryLabel(json.optString("cat", "")));
            input.setText(json.optString("input", "").trim());
            model.setText(ModelCatalog.nameFor(json.optString("model", "")));

            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmDelete(entry);
                }
            });
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    open(entry);
                }
            });
            copyPersian.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    copyPrompt(entry, true);
                }
            });
            copyEnglish.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    copyPrompt(entry, false);
                }
            });
            return row;
        }
    }
}

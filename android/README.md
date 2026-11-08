# Prompt Engine — Android app

The same prompt compiler as the FastAPI service, rebuilt as a native Android app.
No Python, no web server, no Gradle, no third-party library.

```
android/
  AndroidManifest.xml
  res/                                     layouts, styles, strings, drawn icon
  src/com/promptengine/app/
    MainActivity.java                      input, streaming output, analysis, copy/share
    HistoryActivity.java                   the request memory: search, reopen, delete
    SettingsActivity.java                  key, model, prompt override, base URL
    GeminiClient.java                      Gemini REST client (SSE streaming)
    PromptEngine.java                      parser + request wrapper (port of the Python module)
    SystemPrompt.java                      GENERATED from prompt_compiler/prompt_engine.py
    ModelCatalog.java                      model table + prompt categories
    UsageStats.java                        token accounting (port of services/usage.py)
    Store.java                             settings, sealed key, memory, usage, last session
    Crypto.java                            Android Keystore AES-GCM sealing for the API key
    Config.java / ModelInfo.java / PromptResult.java / Ui.java
```

## Why no Gradle

The Android Gradle Plugin would have to be downloaded from Maven on every clean build, and this
network already showed signs of blocking Google endpoints. The app needs nothing but `android.jar`
(`java.net`, `org.json`, `javax.crypto`, classic Views), so it is built directly with
`aapt2` → `javac` → `d8` → `apksigner`. A clean build takes about a minute and works offline.

## Memory

Every successful compilation is stored in the app's private storage, so the tool keeps a
history of what you asked for:

- The **حافظه** button opens a full screen listing past requests with date, detected category,
  model and the Persian input.
- A search box filters across the input text, the model, the category and the generated prompt
  itself.
- Tapping an entry restores that request and its full result on the main screen.
- **✕** on a row deletes one entry (with confirmation); **پاک کردن همه** empties the memory.
- Up to 100 requests are kept, newest first, in `files/history.json`. Writes are serialised and
  run off the main thread.
- Separately, the last session (input + result) is kept in preferences so reopening the app
  shows where you left off. **درخواست جدید** clears that and starts a blank request.

## Build and verify

```sh
sh tools/build_apk.sh          # -> android/build/com.promptengine.app.apk
sh tools/check.sh              # python suite + prompt parity + APK packaging rules
python3 tools/smoke_gemini.py  # optional: exercise the real API with your key
```

`tools/gen_java_prompt.py` derives `SystemPrompt.java` from `prompt_compiler/prompt_engine.py`.
The build refuses to run if that file is stale, and a check mode backs it:

```sh
python3 tools/gen_java_prompt.py --check
```

`tools/check_apk_alignment.py` enforces the targetSdk-30+ packaging rules: `resources.arsc`
stored, and every stored entry starting on a 4-byte boundary. It reads the offset from the
**local** header, because a signer pads that header for alignment while the central directory
keeps the original extra field — reading the central directory reports offsets that are wrong by
a few bytes. `apksigner` applies that padding itself, so no separate alignment step is needed;
the check is what proves it. The SDK's own `zipalign` cannot be used here: it is an x86 binary.

## What changed compared with the web app

Kept identical on purpose: the system prompt policy (byte for byte, generated), the response
schema and the parser's normalisation rules, the model table, and the token-usage accounting.

Fixed along the way:

- **API key at rest.** The web app kept it in plaintext in `config.json`. The app seals it with
  an AES-GCM key held by the Android Keystore and never writes the plaintext to disk. A Keystore
  failure degrades to an obfuscated value rather than losing the key.
- **Key in transit.** The Python client sent `?key=…` in the URL, which leaks into proxies and
  logs. The app sends `x-goog-api-key` instead.
- **Latency.** Instead of waiting up to 180 s for a single JSON blob, the app streams
  `streamGenerateContent` over SSE and extracts `english_prompt` / `persian_prompt` from the
  half-written JSON, so the prompt appears while the model is still writing it.
- **Stale catalog.** The model table is an offline snapshot for the picker, and Settings has a
  "verify key and models" action that merges the live `models.list` result on top of it.
- **Polling.** The web UI polled `/usage` every 5 s. Usage is now computed on demand.
- **No memory.** The web app kept a browser-side result and a small server-side log. The app has
  a real, searchable request memory plus session restore.

Deliberate UI decisions for a phone:

- Compiling runs one request at a time; the primary button turns into a cancel button while a
  request is in flight.
- Analysis blocks with no items are hidden instead of rendering four "nothing here" cards.
- The app registers as a share target and as an `ACTION_PROCESS_TEXT` provider, so any Persian
  text can be sent into it from another app and compiled immediately.
- The English prompt is selectable and has one-tap copy plus the system share sheet, which
  replaces the web app's hardcoded "use in Claude Code / OpenCode" buttons.

## Behaviour notes

- One request per tap, `temperature 0.7`, `maxOutputTokens 8192` — same generation settings as
  the service.
- Usage events are kept for 7 days, request memory for the last 100 compilations.
- If the endpoint is blocked or the key is wrong, the error card distinguishes no-network,
  blocked-network, invalid key, unknown model and rate-limit cases instead of showing one
  generic failure.

## Known gaps

- The launcher icon and the UI use the system font; the web app's bundled Vazirmatn is in
  WOFF2, which Android cannot load from `res/font`, and no converter is available offline.
- The Google AI Studio API has no public quota endpoint, so the RPM/TPM/RPD figures are the
  snapshot values and the meters are informative, not authoritative.
- Output is not streamed into the analysis arrays progressively: those appear once the JSON is
  complete.
- The memory keeps the generated prompt but not the individual tokens used, so a restored entry
  cannot show its own cost.

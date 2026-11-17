# Learning profile backup v8 — verification checklist

Implemented:
- Export of `trigrams`, `bigrams`, `correction_memory`, `learned_words`, `fa_neural_context_mlp_v1`, and `fa_neural_context_examples_v1`.
- Versioned JSON envelope: `format`, `schema_version`, `app`, `created_at_utc`, `learning_data`.
- Android Storage Access Framework file picker; no broad storage permission required.
- Import validates format/version, limits file size to 8 MiB, and writes only allowlisted learning keys.
- Clipboard contents and visual settings are excluded.
- The same JSON can be hosted by the user and downloaded/imported on another installation.

Manual device tests still required:
1. Create several corrections and confirm learned-example count increases.
2. Export to `Download/KeyFarsi-Learning`.
3. Inspect JSON keys; verify clipboard text is absent.
4. Install on a second Android device, import the JSON, and verify example count and learned suggestions are restored.
5. Try malformed JSON, wrong `format`, unsupported schema, and oversized files; import must fail without changing preferences.
6. Test file provider/cloud/host downloads on Android 10+ and Android 16.

Build/test status: source-level implementation only in this environment; Android Gradle build and physical-device tests have not been run.

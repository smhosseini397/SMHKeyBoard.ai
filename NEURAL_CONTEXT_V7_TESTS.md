# KeyFarsi v7 — neural contextual model checks

## Static / compile checks performed
- `PersianSpellChecker.kt` and `PersianNeuralContextModel.kt` compile together with Kotlin compiler against minimal Android/JSON API stubs.
- Neural inference is gated until 3 explicit correction examples.
- Neural automatic replacement is gated until 10 explicit correction examples and a positive score/margin threshold.
- Pairwise training uses the user's chosen correction as positive and up to five close alternatives as negative.
- The model persists weights and example count in private SharedPreferences.
- The IME sends the previous one/two completed words into suggestion ranking and correction training.
- Password/sensitive fields are excluded by the existing IME checks before correction training and correction.

## Manual device acceptance tests still required
1. Type a misspelling and choose the intended correction at least ten times across several contexts.
2. Repeat the same typo in a similar context; verify the chosen word rises in suggestions.
3. Use the same typo in a different context; verify the model does not force the learned correction when its score margin is weak.
4. Confirm auto replacement occurs only after the learning threshold and only on a clear winner.
5. Test Persian, English, password, search, and multiline fields on the target Android device.

This is not a semantic benchmark. No claim of ChatGPT-level or general unseen-sentence understanding is made. APK build and Motorola Edge 50 runtime tests have not yet been performed in this environment.

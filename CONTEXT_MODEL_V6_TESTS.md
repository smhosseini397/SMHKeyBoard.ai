# Context model v6 checks

- Compiled `PersianSpellChecker.kt` with Kotlin/JVM compiler: PASS.
- Ran a focused ranking test using a typo candidate with learned trigram context versus a competing candidate without context: PASS; the trigram-supported candidate ranked first.
- Verified all call sites use the new two-previous-word candidate context API.
- Android APK build and on-device IME test: NOT RUN in this environment (Android SDK/Gradle executable are not installed).

The focused ranking test validates contextual scoring only. It does not establish broad Persian correction accuracy or neural/LLM-level semantic understanding.

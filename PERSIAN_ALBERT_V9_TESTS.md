# Persian ALBERT v9 integration checklist

- [x] Add ONNX Runtime Android dependency.
- [x] Add SentencePiece Unigram model reader for the Persian checkpoint.
- [x] Add ONNX masked-language-model inference wrapper.
- [x] Connect contextual candidate scoring to automatic correction on space.
- [x] Preserve fallback to local spell checker if model files are missing.
- [x] Add reproducible model download/export script.
- [x] Add CI step to fetch official weights, export ONNX assets, and build APK.
- [ ] Run GitHub Actions build and verify exported ONNX graph inputs/outputs.
- [ ] Benchmark inference latency and memory on Motorola Edge 50.
- [ ] Evaluate false-positive corrections on a held-out Persian typo dataset.
- [ ] Tune confidence margin based on measured precision, not guesswork.

The source ZIP does not include model binaries. They are generated during the configured CI build from the official upstream checkpoint.

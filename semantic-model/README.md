# Persian pretrained semantic correction model (v9)

## Upstream checkpoint

- Repository: `shekar-ai/albert-base-v2-persian-zwnj-naab-mlm`
- Architecture: ALBERT masked-language model
- Parameters: 11.5 million
- Vocabulary: 32,004 SentencePiece tokens; ZWNJ is represented
- Upstream license: MIT
- Upstream checkpoint: 45.9 MB safetensors (FP32)
- Upstream model card reports 49.1% top-1 masked-token accuracy on its stated masked-token evaluation.

This is a pretrained masked-language encoder, **not** a generative chat model and not a ready-made typo-correction model. KeyFarsi uses its pseudo-log-likelihood over candidate words as a contextual reranker. The existing Persian spell checker generates candidates; ALBERT helps choose among them using the preceding sentence context. A conservative score margin is required before automatic replacement.

## Android integration

The Android app uses Microsoft ONNX Runtime (`onnxruntime-android`) and a small Kotlin SentencePiece Unigram tokenizer reader. The model is exported to ONNX and placed at:

- `app/src/main/assets/semantic/albert_persian_mlm.onnx`
- `app/src/main/assets/semantic/spiece.model`
- `app/src/main/assets/semantic/special_tokens.json`

The source ZIP intentionally does not contain the 45+ MB upstream weights or generated ONNX binary. The GitHub Actions build downloads the official checkpoint and exports these assets before compiling the APK. This avoids committing large third-party model binaries to the source repository.

To generate locally:

```bash
python -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
python -m pip install transformers==4.53.1 sentencepiece==0.2.0 onnx==1.17.0 safetensors==0.5.3
python scripts/export_persian_albert_onnx.py
gradle --no-daemon assembleDebug
```

## Runtime behavior

- Runs on-device; no network request is made during typing.
- The first semantic correction loads the ONNX model from app assets into app-private storage.
- It scores up to three lexical candidates and up to four subword pieces per candidate.
- If the model assets are missing or inference fails, the keyboard falls back to the existing local correction engine.
- It only auto-replaces when the contextual score margin is clear; otherwise it leaves the user's text unchanged.
- The pretrained model is static. Personal corrections and vocabulary remain in KeyFarsi's separate local learning profile and can be exported/imported independently.

## Important limitations / validation still required

- This is masked-token pseudo-likelihood scoring, not full sentence generation or deep semantic reasoning.
- The Kotlin SentencePiece implementation uses Unicode NFKC plus Persian yeh/kaf normalization; exact equivalence with every upstream Shekar normalizer edge case has not yet been established.
- ONNX export and Android runtime must be exercised by the GitHub Actions build. Latency, RAM, battery, false-correction rate, and behavior on the target Motorola Edge 50 still require device testing.
- Do not enable aggressive automatic replacement until a Persian typo benchmark confirms a sufficiently low false-positive rate.

## License

The upstream ALBERT checkpoint is marked MIT by its publisher. Preserve the upstream model card and attribution when redistributing model binaries. ONNX Runtime and SentencePiece retain their own licenses; see their upstream projects.

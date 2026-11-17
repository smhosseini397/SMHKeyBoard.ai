# KeyFarsi v10 — Persian ALBERT checkpoint selection and integration

## Selected checkpoint

`shekar-ai/albert-base-v2-persian-zwnj-naab-mlm` is the selected Persian encoder. The upstream model card describes it as an ALBERT masked-language model pretrained on the Naab Persian corpus. It reports:

- 11.5 million parameters
- 12 ALBERT layers, hidden size 768, embedding size 128, 12 attention heads
- 32,004 SentencePiece unigram vocabulary entries
- ZWNJ (U+200C) represented explicitly in the vocabulary
- maximum sequence length 512
- MIT license

The model card describes training on approximately 130 GB of cleaned Persian text. It reports 49.1% top-1 accuracy for masked-token prediction on its stated evaluation set. These are upstream-reported figures, not KeyFarsi benchmark results.

## How KeyFarsi uses it

This checkpoint is an encoder trained with masked language modeling. It is **not** a generative assistant and is not itself a complete typo-correction system. KeyFarsi combines it with the local Persian spell-candidate generator: candidate words are inserted after the preceding context, their subword pieces are masked in batches, and ALBERT's token probabilities are used as a contextual reranking signal. A conservative score margin is required before replacing a typed word when the user presses space.

Version 10 improves the integration by scoring up to four lexical candidates and masking all subword pieces of each candidate in one ONNX batch call (instead of one model invocation per piece). Candidate words longer than four SentencePiece pieces are left to the existing local correction engine.

## ZWNJ handling

The upstream SentencePiece model is packaged alongside the exported ONNX graph. The Kotlin tokenizer reads the SentencePiece unigram model and preserves its explicit ZWNJ piece. Persian yeh/kaf normalization is applied before tokenization.

## Build and model assets

The source archive does not embed the third-party model weights. GitHub Actions downloads the selected Hugging Face checkpoint, exports the masked-language model to ONNX, copies the SentencePiece model and special-token IDs into app assets, and then builds the APK. The generated assets are:

- `app/src/main/assets/semantic/albert_persian_mlm.onnx`
- `app/src/main/assets/semantic/spiece.model`
- `app/src/main/assets/semantic/special_tokens.json`

## Limitations and required validation

- Masked-token likelihood is a contextual ranking signal, not full semantic reasoning or sentence generation.
- The model card itself cautions that the model is weak at factual cloze completion and recommends it as a representation model / fine-tuning starting point.
- The upstream 49.1% masked-token score must not be presented as typo-correction accuracy.
- KeyFarsi still needs a held-out Persian typo benchmark, false-positive measurement, ONNX export verification, and real-device latency/RAM/battery tests on the Motorola Edge 50.
- Until those tests pass, automatic correction remains conservative and falls back to the existing local engine if model assets or inference are unavailable.

## Upstream

Model card: https://huggingface.co/shekar-ai/albert-base-v2-persian-zwnj-naab-mlm

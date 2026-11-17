# Context-aware Persian correction and personal learning

KeyFarsi's correction path is deliberately local and privacy-preserving. It is not a hosted LLM and does not understand arbitrary meaning at ChatGPT level. It combines:

1. Persian character normalization (Arabic Yeh/Kaf variants and ZWNJ-insensitive matching).
2. Weighted edit distance and Persian keyboard-neighbour substitutions.
3. Candidate ranking using word frequency and locally learned previous-word -> next-word counts.
4. A personal typo map: when the user taps a suggestion, the exact typed form is stored with the chosen correction. The next time the same typo is entered, the learned correction is offered first and applied when the user commits the word with a space.
5. Conservative automatic replacement: one-cost errors can be corrected when the candidate is clearly better; two-cost errors require repeated contextual support or strong personal frequency. Ambiguous cases remain suggestions.

## What is learned

- Words the user commits are counted in the personal vocabulary.
- Adjacent word pairs are counted for local context ranking.
- A manually selected suggestion teaches an exact typo-to-correction mapping.
- Password fields are excluded from vocabulary and correction learning.

## Limitations

The current contextual model is a lightweight local statistical model, not a neural language model. It uses nearby word context rather than full-sentence semantic understanding. It should be evaluated with real Persian typing samples before relaxing the automatic-correction thresholds.

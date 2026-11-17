#!/usr/bin/env python3
"""Download the MIT-licensed Persian ALBERT checkpoint and export it for Android ONNX Runtime."""
from pathlib import Path
import json
import shutil
import torch
from transformers import AutoModelForMaskedLM, AutoTokenizer

REPO = "shekar-ai/albert-base-v2-persian-zwnj-naab-mlm"
OUT = Path("app/src/main/assets/semantic")
OUT.mkdir(parents=True, exist_ok=True)

tokenizer = AutoTokenizer.from_pretrained(REPO, use_fast=False)
model = AutoModelForMaskedLM.from_pretrained(REPO).cpu().eval()

# Keep exact special-token ids from the upstream tokenizer; never guess these ids.
special = {
    "cls_token_id": int(tokenizer.cls_token_id),
    "sep_token_id": int(tokenizer.sep_token_id),
    "mask_token_id": int(tokenizer.mask_token_id),
    "unk_token_id": int(tokenizer.unk_token_id),
    "pad_token_id": int(tokenizer.pad_token_id),
}
(OUT / "special_tokens.json").write_text(json.dumps(special, ensure_ascii=False, indent=2), encoding="utf-8")

spiece = Path(tokenizer.vocab_file)
shutil.copyfile(spiece, OUT / "spiece.model")

class ExportWrapper(torch.nn.Module):
    def __init__(self, wrapped):
        super().__init__()
        self.wrapped = wrapped
    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.wrapped(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
            return_dict=False,
        )[0]

wrapper = ExportWrapper(model).eval()
ids = torch.tensor([[special["cls_token_id"], special["mask_token_id"], special["sep_token_id"]]], dtype=torch.long)
mask = torch.ones_like(ids)
types = torch.zeros_like(ids)
torch.onnx.export(
    wrapper,
    (ids, mask, types),
    str(OUT / "albert_persian_mlm.onnx"),
    input_names=["input_ids", "attention_mask", "token_type_ids"],
    output_names=["logits"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "sequence"},
        "attention_mask": {0: "batch", 1: "sequence"},
        "token_type_ids": {0: "batch", 1: "sequence"},
        "logits": {0: "batch", 1: "sequence"},
    },
    opset_version=17,
    do_constant_folding=True,
    dynamo=False,
)
print("Exported Persian ALBERT ONNX assets:")
for path in sorted(OUT.iterdir()):
    print(f"{path}: {path.stat().st_size:,} bytes")

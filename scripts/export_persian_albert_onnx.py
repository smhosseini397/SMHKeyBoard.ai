#!/usr/bin/env python3
"""Download the MIT-licensed Persian ALBERT checkpoint and export it for Android ONNX Runtime."""
from pathlib import Path
import json
import tempfile
import torch
from transformers import AutoModelForMaskedLM, AlbertTokenizer

REPO = "shekar-ai/albert-base-v2-persian-zwnj-naab-mlm"
OUT = Path("app/src/main/assets/semantic")
OUT.mkdir(parents=True, exist_ok=True)

# استفاده از AlbertTokenizer (slow) به جای AutoTokenizer، تا از دسترسی به vocab_file پشتیبانی شود
tokenizer = AlbertTokenizer.from_pretrained(REPO)
model = AutoModelForMaskedLM.from_pretrained(REPO).cpu().eval()

# حفظ شناسه‌های دقیق توکن‌های ویژه
special = {
    "cls_token_id": int(tokenizer.cls_token_id),
    "sep_token_id": int(tokenizer.sep_token_id),
    "mask_token_id": int(tokenizer.mask_token_id),
    "unk_token_id": int(tokenizer.unk_token_id),
    "pad_token_id": int(tokenizer.pad_token_id),
}
(OUT / "special_tokens.json").write_text(json.dumps(special, ensure_ascii=False, indent=2), encoding="utf-8")

# روش صحیح: استفاده از save_vocabulary برای استخراج فایل sentencepiece
with tempfile.TemporaryDirectory() as tmp_dir:
    saved_files = tokenizer.save_vocabulary(tmp_dir)
    # saved_files یک tuple است که مسیر فایل‌های ذخیره شده را برمی‌گرداند؛ معمولاً شامل spiece.model است
    spiece_path = None
    for f in saved_files:
        if f.endswith("spiece.model"):
            spiece_path = Path(f)
            break
    if spiece_path is None:
        # در صورت عدم تطابق، از نام فایل استاندارد استفاده می‌کنیم
        spiece_path = Path(tmp_dir) / "spiece.model"
    if not spiece_path.exists():
        raise FileNotFoundError(f"spiece.model در {tmp_dir} یافت نشد")
    # کپی به پوشه خروجی
    import shutil
    shutil.copyfile(spiece_path, OUT / "spiece.model")

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

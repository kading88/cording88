"""Create the self-contained 5,000-row demo CSV from the local SCX dataset."""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--rows", type=int, default=5000)
    args = parser.parse_args()
    df = pd.read_csv(args.source)
    df.columns = [str(c).strip() for c in df.columns]
    if df.columns.duplicated().any():
        raise ValueError("Normalize duplicate column names before sampling")
    if "Label" not in df:
        raise ValueError("Expected a Label column")
    labels = df["Label"].astype(str).str.strip()
    df["Label"] = labels
    df.insert(0, "__source_row", np.arange(2, len(df) + 2))
    counts = labels.value_counts().sort_index()
    size = min(args.rows, len(df))
    exact = counts / counts.sum() * size
    allocation = np.floor(exact).astype(int)
    for label in (exact - allocation).sort_values(ascending=False).index[:size - int(allocation.sum())]:
        allocation[label] += 1
    pieces = [df[df["Label"] == label].sample(n=int(n), random_state=42) for label, n in allocation.items()]
    sample = pd.concat(pieces).sort_values("__source_row")
    data = ROOT / "data"
    data.mkdir(exist_ok=True)
    sample.to_csv(data / "cic-sample.csv", index=False, encoding="utf-8", float_format="%.12g")
    with args.source.open("rb") as stream:
        source_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    info = {
        "source_file": args.source.name, "source_sha256": source_hash,
        "source_rows": len(df), "source_labels": {str(k): int(v) for k, v in counts.items()},
        "sample_rows": len(sample), "sample_labels": {str(k): int(v) for k, v in allocation.items()},
        "sampling_seed": 42, "sampling": "proportional stratified sampling by source label",
        "source_row_definition": "CSV physical record number including header as row 1",
        "note": "Subset of the supplied local SCX CSV, which contains BENIGN, DDoS and Syn. Not a claim that this file is an unmodified official CIC release.",
    }
    (data / "sample-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(info, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()

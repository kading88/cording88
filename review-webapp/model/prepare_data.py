"""Train a small SOM and insert candidate predictions; preserve existing reviews.

Adapted from SCX/ordinary_scx.py: MiniSom training, neuron majority votes,
and purity * 1/(1+2*quantization_error), with the small-neuron penalty.
"""
from pathlib import Path
from collections import Counter
import argparse
import hashlib
import json
import os
import sys
import numpy as np
import pandas as pd
from minisom import MiniSom
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
import pymysql

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from local_config import load_env

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=ROOT / "data" / "cic-sample.csv")
    parser.add_argument("--output-only", action="store_true", help="Compute and save local artifacts without connecting to MySQL")
    args = parser.parse_args()
    load_env(ROOT)
    output = ROOT / "model" / "output"
    output.mkdir(exist_ok=True)
    df = pd.read_csv(args.csv)
    df.columns = [str(c).strip() for c in df.columns]
    if df.columns.duplicated().any():
        raise ValueError("Duplicate feature names must be normalized")
    required = {"Label", "__source_row"}
    if not required.issubset(df.columns):
        raise ValueError("Use the included sample CSV, or create one with make_sample.py")
    mapping = {"BENIGN": 0, "DDoS": 1, "Syn": 1}
    source_labels = df["Label"].astype(str).str.strip()
    if unknown := set(source_labels) - set(mapping):
        raise ValueError(f"Unsupported source labels: {sorted(unknown)}")
    y = source_labels.map(mapping).to_numpy(dtype=np.int64)
    ids = df["__source_row"].to_numpy(dtype=np.int64)
    if len(set(ids)) != len(ids):
        raise ValueError("Source row identifiers must be unique")
    features = df.drop(columns=["Label", "__source_row"]).apply(pd.to_numeric, errors="raise")
    features = features.replace([np.inf, -np.inf], np.nan)
    names = list(features.columns)
    indices = np.arange(len(df))
    seed_indices, candidate_indices = train_test_split(indices, train_size=0.2, random_state=42, stratify=y)
    # Fit all imputation and scaling parameters on the training subset only.
    medians = features.iloc[seed_indices].median().fillna(0.0)
    filled = features.fillna(medians).to_numpy(dtype=np.float64)
    scaler = StandardScaler().fit(filled[seed_indices])
    x_seed = scaler.transform(filled[seed_indices])
    x_candidate = scaler.transform(filled[candidate_indices])
    params = {"grid": [20, 20], "sigma": 1.5, "learning_rate": 0.5,
              "random_seed": 42, "train_fraction": 0.2,
              "iterations": max(1000, len(x_seed) * 2), "algorithm_revision": "scx-review-som-v1"}
    csv_hash = hashlib.sha256(args.csv.read_bytes()).hexdigest()
    identity = json.dumps({"csv_sha256": csv_hash, "parameters": params}, sort_keys=True).encode()
    version = "som-v1-" + hashlib.sha256(identity).hexdigest()[:12]
    print(f"Training {version}: {len(seed_indices)} seed rows, {len(candidate_indices)} candidate rows, {len(names)} features", flush=True)
    som = MiniSom(20, 20, len(names), sigma=1.5, learning_rate=0.5, random_seed=42)
    som.train_random(x_seed, params["iterations"])
    votes = {}
    for x, label in zip(x_seed, y[seed_indices]):
        votes.setdefault(som.winner(x), []).append(int(label))
    labels = {}; purities = {}; counts = {}
    for neuron, values in votes.items():
        winner, count = Counter(values).most_common(1)[0]
        labels[neuron] = winner; purities[neuron] = count / len(values); counts[neuron] = len(values)
    # Follow the SCX traversal and nearest-neuron labeling rules; assign low scores to empty neurons.
    for x in range(20):
        for z in range(20):
            if (x, z) not in labels:
                nearest = min(labels, key=lambda n: (n[0] - x)**2 + (n[1] - z)**2)
                labels[(x, z)] = labels[nearest]; purities[(x, z)] = 0.1; counts[(x, z)] = 0
    source_info_path = ROOT / "data" / "sample-info.json"
    source_info = json.loads(source_info_path.read_text(encoding="utf-8")) if source_info_path.exists() and args.csv.resolve() == (ROOT / "data" / "cic-sample.csv").resolve() else {}
    source_hash = source_info.get("source_sha256", csv_hash)
    results = []
    for position, x in zip(candidate_indices, x_candidate):
        neuron = som.winner(x)
        qe = float(np.linalg.norm(x - som.get_weights()[neuron]))
        score = purities[neuron] / (1 + 2 * qe)
        if counts[neuron] < 5:
            score *= 0.5
        original = features.iloc[position]
        raw = {str(k): None if pd.isna(v) else float(v) for k, v in original.items()}
        results.append({
            "source_key": f"{source_hash}:{int(ids[position])}", "source_row": int(ids[position]),
            "model_version": version, "features_json": json.dumps(raw, allow_nan=False),
            "predicted_label": "ATTACK" if labels[neuron] else "NORMAL", "score": float(score),
            "quantization_error": qe, "som_x": int(neuron[0]), "som_y": int(neuron[1]),
            "neuron_count": counts[neuron], "cluster_purity": purities[neuron],
        })
    results.sort(key=lambda r: r["source_row"])
    np.savez_compressed(output / "som-model.npz", weights=som.get_weights(), mean=scaler.mean_, scale=scaler.scale_,
        medians=medians.to_numpy(), feature_names=np.array(names), seed_rows=ids[seed_indices],
        candidate_rows=ids[candidate_indices], labels=np.array([[labels[(x,z)] for z in range(20)] for x in range(20)]),
        purities=np.array([[purities[(x,z)] for z in range(20)] for x in range(20)]),
        neuron_counts=np.array([[counts[(x,z)] for z in range(20)] for x in range(20)]))
    with (output / "predictions.jsonl").open("w", encoding="utf-8") as f:
        for result in results:
            f.write(json.dumps(result, ensure_ascii=False) + "\n")
    summary = {"model_version": version, "parameters": params, "csv_sha256": csv_hash,
        "source_sha256": source_hash, "seed_rows": len(seed_indices), "candidate_rows": len(results),
        "features": names, "prediction_counts": dict(Counter(r["predicted_label"] for r in results)),
        "score_range": [min(r["score"] for r in results), max(r["score"] for r in results)],
        "score_note": "Heuristic ranking score, not a calibrated probability",
        "preprocessing": "Training-only medians and StandardScaler; non-finite values treated as missing",
        "source_code": "Adapted from supplied SCX ordinary_scx.py; all candidates retained, no high-score filtering"}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.output_only:
        print(f"Saved model and {len(results)} predictions to {output}", flush=True)
        return
    password = os.environ.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("DB_PASSWORD is missing; run setup.ps1 first")
    connection = pymysql.connect(host=os.getenv("DB_HOST", "127.0.0.1"), port=int(os.getenv("DB_PORT", "3307")),
        user=os.getenv("DB_USER", "scx_app"), password=password, database=os.getenv("DB_NAME", "scx_review"),
        charset="utf8mb4", autocommit=False)
    with connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT DISTINCT model_version FROM traffic_records LIMIT 2")
            existing = {r[0] for r in cursor.fetchall()}
            if existing and existing != {version}:
                raise RuntimeError("This demo database already contains another model version. Use a new database to keep prior reviews intact.")
            fields = list(results[0])
            sql = "INSERT INTO traffic_records (" + ",".join(fields) + ") VALUES (" + ",".join(["%s"] * len(fields)) + ") ON DUPLICATE KEY UPDATE source_key=traffic_records.source_key"
            inserted = cursor.executemany(sql, [tuple(r[f] for f in fields) for r in results])
        connection.commit()
    print(f"Complete: {len(results)} candidates, {inserted} new rows inserted. Existing records and reviews preserved.", flush=True)

if __name__ == "__main__":
    main()

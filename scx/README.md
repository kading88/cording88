# SCX research program

`SCX.py` is the main research program from the existing SCX project. It uses a self-organizing map (SOM) to generate and select pseudo-labels, then retrains an XGBoost classifier using the initial labeled data and accepted pseudo-labeled samples.

This copy retains the existing computation and configuration. Comments, docstrings, and console messages are in English. Formatting was cleaned up for reading. The separate `ordinary_scx.py` experiment runner is not included.

## Files

| File | Purpose |
| --- | --- |
| `SCX.py` | Configuration, preprocessing, SOM, pseudo-label selection, XGBoost retraining, evaluation, and plots. |
| `requirements.txt` | Dependency versions recorded with the local research project. |

Datasets are not included. The repository ignores dataset files and generated experiment output under this folder.

## Run on Windows

Use Python 3.12 and run the following commands from the repository root. Activating the environment is optional because the commands call its Python executable directly.

```powershell
py -3.12 -m venv .\scx\.venv
.\scx\.venv\Scripts\python.exe -m pip install -r .\scx\requirements.txt
```

Put your labeled research CSV in `scx/`, or set an absolute path in `Config.CSV_FILE_PATH`. Then run from the `scx` directory:

```powershell
Set-Location .\scx
.\.venv\Scripts\python.exe .\SCX.py
```

The existing default filename is `Friday-WorkingHours-Afternoon-DDos.pcap_ISCX.csv`. The path is resolved relative to the working directory. A filename alone does not guarantee schema compatibility; check the input requirements below.

The program prints evaluation results, including accuracy, F1, and false positive rate, and opens four Matplotlib figures after the learning loop. Set `PLOT_RESULTS = False` when interactive plot windows are unnecessary. This main program does not save result files or models automatically.

## Input requirements

- The final CSV column must contain ground-truth labels. `BENIGN`/`benign`/`Benign` and `NORMAL`/`normal`/`Normal`, after trimming whitespace, map to normal class 0. All other labels map to attack class 1.
- Use both normal and attack examples, with enough examples per class for stratified splitting.
- The program uses the existing `features_to_remove` list in `load_and_preprocess_data`. Those exact columns must be present because the original code drops them without ignoring missing columns. Dataset variants use different names, so align the CSV schema or deliberately adjust that list for your experiment.
- The default dependencies pin pandas 2.2.3. The original preprocessing assumes the older object/string dtype behavior; pandas 3 is not covered by these requirements.

The PCAP extractor in the parent directory produces `NeedManualLabel` and its own feature names. Add verified labels and reconcile the schema before using its CSV for this experiment. The default Java output is not directly ready for SCX training.

## Current configuration

The values below describe the checked-in research source, not a claim about the settings of a previously presented evaluation run.

| Setting | Value |
| --- | --- |
| Initial labeled data | 10% |
| Held-out evaluation data | 30% |
| Stream treated as unlabeled | 60% |
| Maximum CSV rows | `None`, meaning the full file |
| Main random seed | 18 |
| Second split random seed | 123 |
| SOM grid | 20 x 20 |
| Pseudo-label confidence threshold | 0.85 |
| Quantization error cutoff | 85th percentile within the batch |
| Learning batches | 10 |
| Maximum accumulated training samples | 300,000 |
| XGBoost threads | -1, meaning all available cores |

For a quick local check, explicitly reduce `MAX_DATA_ROWS` before running. Such a run uses the first rows of the file and must not be reported as a full-dataset experiment. Saved results from another configuration must retain their own data split, row limit, and seed when comparing them with this program.

## Reading order

1. `Config`: dataset path and experiment parameters.
2. `load_and_preprocess_data` and `_preprocess_features`: labels and input features.
3. `train_initial_models` and `_assign_cluster_labels`: initial models and neuron labels.
4. `generate_pseudo_labels`: confidence and distance filters.
5. `update_xgboost_model`: accumulate selected samples and fit a new classifier.
6. `continuous_learning_step`: run one update and record performance.
7. `evaluate_model`: classification metrics and confusion matrix.
8. `main`: dataset splitting and the complete experiment loop.

In the offline experiment, ground-truth stream labels are retained to report pseudo-label accuracy. They are not used to select pseudo-labels or train the updated classifier. This source does not read MySQL tables or automatically connect the PCAP extraction pipeline to the training loop.

# SCX research program. See README.md for data requirements and configuration.
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from minisom import MiniSom
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
import xgboost as xgb
from collections import Counter
import time
import warnings
try:
    import psutil
except (ImportError, ModuleNotFoundError):
    psutil = None
import os
warnings.filterwarnings('ignore')

# Adjust experiment parameters here before running the program.
class Config:
    """Central configuration for data, models, and the learning loop."""
    # This path is relative to the current working directory. Dataset files stay local.
    CSV_FILE_PATH = 'Friday-WorkingHours-Afternoon-DDos.pcap_ISCX.csv'
    # None reads the full CSV; use an integer only for an explicitly limited run.
    MAX_DATA_ROWS = None
    RANDOM_SEED = 18
    INITIAL_TRAINING_RATIO = 0.1
    TEST_RATIO = 0.3
    UNLABELED_RATIO = 0.6
    SOM_SIZE = (20, 20)
    SOM_LEARNING_RATE = 0.5
    SOM_SIGMA = 1.5
    SOM_ITERATION_MULTIPLIER = 2
    # -1 uses all available cores; choose 1 or 2 for a CPU-limited run.
    XGBOOST_N_JOBS = -1
    XGBOOST_V1_N_ESTIMATORS = 10
    XGBOOST_V1_MAX_DEPTH = 6
    XGBOOST_V1_LEARNING_RATE = 0.1
    XGBOOST_UPDATE_N_ESTIMATORS = 10
    XGBOOST_UPDATE_MAX_DEPTH = 6
    XGBOOST_UPDATE_LEARNING_RATE = 0.1
    XGBOOST_UPDATE_REG_ALPHA = 0.1
    XGBOOST_UPDATE_REG_LAMBDA = 0.1
    XGBOOST_UPDATE_SUBSAMPLE = 0.8
    XGBOOST_UPDATE_COLSAMPLE = 0.8
    MAX_TRAINING_SAMPLES = 300000
    CONFIDENCE_THRESHOLD = 0.85
    PSEUDO_LABEL_RATIO = 100
    NUM_LEARNING_BATCHES = 10
    BATCH_PROCESSING_DELAY = 0
    VERBOSE = True
    PLOT_RESULTS = True

    @classmethod
    def print_config(cls):
        """Print the active configuration."""
        print('Current configuration:')
        print('=' * 60)
        print(f'CSV file: {cls.CSV_FILE_PATH}')
        print(f"Maximum data rows: {(cls.MAX_DATA_ROWS if cls.MAX_DATA_ROWS else 'all')}")
        print(f'SOM grid: {cls.SOM_SIZE}, learning rate: {cls.SOM_LEARNING_RATE}')
        print(f'XGBoost threads: {cls.XGBOOST_N_JOBS} (configurable resource limit)')
        print(f'XGBoost v1.0: {cls.XGBOOST_V1_N_ESTIMATORS} trees, depth {cls.XGBOOST_V1_MAX_DEPTH}')
        print(f'Updated XGBoost: {cls.XGBOOST_UPDATE_N_ESTIMATORS} trees, depth {cls.XGBOOST_UPDATE_MAX_DEPTH}')
        print(f'Maximum training samples: {cls.MAX_TRAINING_SAMPLES}')
        print(f'Confidence threshold: {cls.CONFIDENCE_THRESHOLD}')
        print(f'Learning batches: {cls.NUM_LEARNING_BATCHES}')
        print('=' * 60)

class SOMXGBoostContinuousLearning:
    """SOM-assisted XGBoost retraining for intrusion detection."""

    def __init__(self, config=None):
        """Initialize the system with the supplied configuration or Config by default."""
        if config is None:
            config = Config
        self.config = config
        self.som_size = config.SOM_SIZE
        self.som_learning_rate = config.SOM_LEARNING_RATE
        self.som_sigma = config.SOM_SIGMA
        self.som_iteration_multiplier = config.SOM_ITERATION_MULTIPLIER
        self.som = None
        self.xgboost_current = None
        self.model_version = 1.0
        self.scaler = None
        self.label_encoders = {}
        self.feature_names = None
        self.cluster_labels = {}
        self.cluster_confidence = {}
        self.neuron_votes = {}
        self.confidence_threshold = config.CONFIDENCE_THRESHOLD
        self.max_training_samples = config.MAX_TRAINING_SAMPLES
        self.pseudo_label_ratio = config.PSEUDO_LABEL_RATIO
        self.random_seed = config.RANDOM_SEED
        self.performance_history = []
        self.pseudo_label_history = []
        self.training_data_cache = {'X': None, 'y': None}

    def load_and_preprocess_data(self, csv_file, max_rows=None):
        """Load a labeled CSV and prepare its model features."""
        print(f'Loading CSV: {csv_file}')
        try:
            if max_rows is not None:
                print(f'Maximum rows to read: {max_rows:,}')
                df = pd.read_csv(csv_file, nrows=max_rows)
            else:
                df = pd.read_csv(csv_file)
            print(f'Loaded {df.shape[0]:,} rows and {df.shape[1]} columns')
            if len(df) > 100000 and max_rows is None:
                print(f'Large dataset ({len(df):,} rows); consider setting max_rows for a shorter run.')
            label_column = df.columns[-1]
            print(f"Using the last column as the label: '{label_column}'")
            original_label_counts = df[label_column].value_counts()
            print(f'\nOriginal label distribution:')
            for label, count in original_label_counts.items():
                print(f'  {label}: {count:,} samples ({count / len(df) * 100:.1f}%)')
            # Known normal labels map to 0; all other labels map to attack class 1.
            normal_labels = ['BENIGN', 'benign', 'Benign', 'NORMAL', 'normal', 'Normal']
            df['class_label'] = df[label_column].apply(lambda x: 0 if str(x).strip() in normal_labels else 1)
            converted_label_counts = df['class_label'].value_counts()
            normal_count = converted_label_counts.get(0, 0)
            attack_count = converted_label_counts.get(1, 0)
            print(f'\nBinary label distribution:')
            print(f'  Normal: {normal_count:,} samples ({normal_count / len(df) * 100:.1f}%)')
            print(f'  Attack: {attack_count:,} samples ({attack_count / len(df) * 100:.1f}%)')
            df = df.drop(label_column, axis=1)
            # Keep the original schema-specific removal list for reproducibility.
            features_to_remove = ['Flow ID', 'Src IP', 'Src Port', 'Dst IP', 'Dst Port', 'Timestamp', 'Fwd PSH Flags', 'Fwd URG Flags', 'Bwd URG Flags', 'URG Flag Cnt', 'CWE Flag Count', 'ECE Flag Cnt', 'Fwd Byts/b Avg', 'Fwd Pkts/b Avg', 'Fwd Blk Rate Avg', 'Bwd Byts/b Avg', 'Bwd Pkts/b Avg', 'Bwd Blk Rate Avg', 'Init Fwd Win Byts', 'Fwd Seg Size Min']
            df = df.drop(columns=features_to_remove)
            print(f'Removed {len(features_to_remove)} identity-related or constant features')
            df_processed = self._preprocess_features(df)
            return df_processed
        except Exception as e:
            print(f'Data loading failed: {e}')
            raise

    def _preprocess_features(self, df):
        """Encode categorical fields and handle non-finite or missing values."""
        if self.config.VERBOSE:
            print('\nPreprocessing features...')
        df_processed = df.copy()
        categorical_features = []
        numerical_features = []
        for col in df_processed.columns:
            if col == 'class_label':
                continue
            if df_processed[col].dtype == 'object' or not np.issubdtype(df_processed[col].dtype, np.number):
                categorical_features.append(col)
            else:
                numerical_features.append(col)
        if self.config.VERBOSE:
            print(f'Categorical features: {len(categorical_features)}')
            print(f'Numerical features: {len(numerical_features)}')
        for col in categorical_features:
            le = LabelEncoder()
            df_processed[col] = le.fit_transform(df_processed[col].astype(str))
            self.label_encoders[col] = le
        if self.config.VERBOSE:
            print(f'Handling infinite and missing values...')
        for col in df_processed.columns:
            if col == 'class_label':
                continue
            df_processed[col] = pd.to_numeric(df_processed[col], errors='coerce')
            inf_count = np.isinf(df_processed[col]).sum()
            if inf_count > 0:
                df_processed[col] = df_processed[col].replace([np.inf, -np.inf], np.nan)
            null_count = df_processed[col].isnull().sum()
            if null_count > 0:
                median_val = df_processed[col].median()
                df_processed[col].fillna(median_val, inplace=True)
        total_inf = sum((np.isinf(df_processed[col]).sum() for col in df_processed.columns if col != 'class_label'))
        total_nan = sum((df_processed[col].isnull().sum() for col in df_processed.columns if col != 'class_label'))
        if total_inf == 0 and total_nan == 0:
            if self.config.VERBOSE:
                print(f'Data cleanup complete; no infinite or missing values remain.')
        else:
            print(f'Remaining non-finite values: infinite={total_inf}, missing={total_nan}')
        self.feature_names = [col for col in df_processed.columns if col != 'class_label']
        if self.config.VERBOSE:
            print(f'Preprocessing complete; feature count: {len(self.feature_names)}')
        return df_processed

    def train_initial_models(self, X_train, y_train):
        """Train the initial SOM and XGBoost models."""
        print('\n' + '=' * 60)
        print('Training initial models')
        print('=' * 60)
        if hasattr(X_train, 'reset_index'):
            X_train = X_train.reset_index(drop=True)
        if hasattr(y_train, 'reset_index'):
            y_train = y_train.reset_index(drop=True)
        print('Standardizing features...')
        # Fit scaling on the initial training set and reuse it for later samples.
        self.scaler = StandardScaler()
        X_scaled = self.scaler.fit_transform(X_train)
        print(f'\nTraining SOM (grid: {self.som_size})...')
        self.som = MiniSom(x=self.som_size[0], y=self.som_size[1], input_len=X_scaled.shape[1], sigma=self.som_sigma, learning_rate=self.som_learning_rate, random_seed=self.random_seed)
        num_iterations = max(1000, len(X_scaled) * self.som_iteration_multiplier)
        self.som.train_random(data=X_scaled, num_iteration=num_iterations)
        print(f'SOM training complete (iterations: {num_iterations:,})')
        print('\nAssigning SOM neuron labels...')
        self._assign_cluster_labels(X_scaled, y_train)
        print(f'\nTraining initial XGBoost model v{self.model_version:.1f}...')
        self.xgboost_current = xgb.XGBClassifier(n_estimators=self.config.XGBOOST_V1_N_ESTIMATORS, max_depth=self.config.XGBOOST_V1_MAX_DEPTH, learning_rate=self.config.XGBOOST_V1_LEARNING_RATE, n_jobs=self.config.XGBOOST_N_JOBS, random_state=self.random_seed, eval_metric='logloss', use_label_encoder=False)
        start_time = time.time()
        self.xgboost_current.fit(X_scaled, y_train)
        training_time = time.time() - start_time
        print(f'XGBoost training complete; elapsed: {training_time:.2f} seconds')
        print(f'Model parameters: {self.config.XGBOOST_V1_N_ESTIMATORS} trees, depth {self.config.XGBOOST_V1_MAX_DEPTH}, learning rate {self.config.XGBOOST_V1_LEARNING_RATE}')
        self.training_data_cache['X'] = X_scaled.copy()
        self.training_data_cache['y'] = np.array(y_train).copy()
        return self

    def _assign_cluster_labels(self, X_scaled, y_train):
        """Assign neuron labels by majority vote from the initial labeled data."""
        if hasattr(y_train, 'values'):
            y_train_array = y_train.values
        else:
            y_train_array = np.array(y_train)
        self.neuron_votes = {}
        for i, sample in enumerate(X_scaled):
            winner = self.som.winner(sample)
            if winner not in self.neuron_votes:
                self.neuron_votes[winner] = []
            self.neuron_votes[winner].append(y_train_array[i])
        if self.config.VERBOSE:
            print(f'Sample-receiving neurons: {len(self.neuron_votes)}')
        for neuron, votes in self.neuron_votes.items():
            vote_counts = Counter(votes)
            majority_label = vote_counts.most_common(1)[0][0]
            majority_count = vote_counts.most_common(1)[0][1]
            confidence = majority_count / len(votes)
            label_name = 'normal' if majority_label == 0 else 'attack'
            self.cluster_labels[neuron] = label_name
            self.cluster_confidence[neuron] = confidence
        for x in range(self.som_size[0]):
            for y in range(self.som_size[1]):
                if (x, y) not in self.cluster_labels:
                    nearest_label = self._find_nearest_labeled_neuron(x, y)
                    self.cluster_labels[x, y] = nearest_label
                    self.cluster_confidence[x, y] = 0.1
        normal_count = sum((1 for label in self.cluster_labels.values() if label == 'normal'))
        attack_count = len(self.cluster_labels) - normal_count
        avg_confidence = np.mean(list(self.cluster_confidence.values()))
        if self.config.VERBOSE:
            print(f'Neuron labels assigned:')
            print(f'   Normal neurons: {normal_count}')
            print(f'   Attack neurons: {attack_count}')
            print(f'   Mean confidence: {avg_confidence:.3f}')
        print(f'   Neurons activated and labeled by training samples: {len(self.neuron_votes)} / {self.som_size[0] * self.som_size[1]}')

    def _find_nearest_labeled_neuron(self, x, y):
        """Find the nearest neuron with an assigned label."""
        min_distance = float('inf')
        nearest_label = 'normal'
        if not self.cluster_labels:
            return nearest_label
        for (nx, ny), label in self.cluster_labels.items():
            distance = np.sqrt((x - nx) ** 2 + (y - ny) ** 2)
            if distance < min_distance:
                min_distance = distance
                nearest_label = label
        return nearest_label

    def generate_pseudo_labels(self, X_unlabeled, y_true=None):
        """Generate and filter SOM pseudo-labels; optionally report their accuracy."""
        if self.config.VERBOSE:
            print(f'\nGenerating pseudo-labels for {len(X_unlabeled):,} unlabeled samples...')
        if self.som is None:
            raise ValueError('SOM is not trained; call train_initial_models first.')
        if hasattr(X_unlabeled, 'reset_index'):
            X_unlabeled = X_unlabeled.reset_index(drop=True)
        if y_true is not None and hasattr(y_true, 'reset_index'):
            y_true = y_true.reset_index(drop=True)
        X_scaled = self.scaler.transform(X_unlabeled)
        pseudo_labels = []
        confidence_scores = []
        explanations = []
        qe_scores = []
        for i, sample in enumerate(X_scaled):
            winner = self.som.winner(sample)
            cluster_label = self.cluster_labels.get(winner, 'normal')
            cluster_confidence = self.cluster_confidence.get(winner, 0.1)
            winner_weights = self.som.get_weights()[winner]
            qe = np.linalg.norm(sample - winner_weights)
            qe_scores.append(qe)
            pseudo_label = 1 if cluster_label == 'attack' else 0
            pseudo_labels.append(pseudo_label)
            qe_confidence = 1 / (1 + qe * 2)
            combined_confidence = cluster_confidence * qe_confidence
            neuron_sample_count = len(self.neuron_votes.get(winner, []))
            if neuron_sample_count < 5:
                combined_confidence *= 0.5
            confidence_scores.append(combined_confidence)
        confidence_array = np.array(confidence_scores)
        qe_array = np.array(qe_scores)
        basic_mask = confidence_array >= self.confidence_threshold
        # Combine the confidence threshold with the 85th percentile distance filter.
        qe_threshold = np.percentile(qe_array, 85)
        qe_mask = qe_array <= qe_threshold
        final_mask = basic_mask & qe_mask
        high_confidence_indices = np.where(final_mask)[0]
        if len(high_confidence_indices) > 0:
            pseudo_array = np.array(pseudo_labels)
            X_pseudo = X_scaled[high_confidence_indices]
            y_pseudo = pseudo_array[high_confidence_indices]
            confidences_pseudo = confidence_array[high_confidence_indices]
            max_pseudo_samples = min(len(high_confidence_indices), int(len(self.training_data_cache['X']) * self.pseudo_label_ratio))
            if len(high_confidence_indices) > max_pseudo_samples:
                top_indices = np.argsort(confidences_pseudo)[-max_pseudo_samples:]
                final_high_indices = high_confidence_indices[top_indices]
                X_pseudo = X_scaled[final_high_indices]
                y_pseudo = pseudo_array[final_high_indices]
                confidences_pseudo = confidence_array[final_high_indices]
            else:
                final_high_indices = high_confidence_indices
            # Hidden ground truth is used only to report pseudo-label accuracy.
            if y_true is not None:
                y_batch_true_subset = y_true.iloc[final_high_indices] if hasattr(y_true, 'iloc') else y_true[final_high_indices]
                ps_accuracy = accuracy_score(y_batch_true_subset, y_pseudo)
                print(f'Selected pseudo-label accuracy: {ps_accuracy:.4f} (based on {len(y_pseudo)} samples)')
            if self.config.VERBOSE:
                print(f'Generated {len(X_pseudo):,} selected pseudo-labeled samples')
                if len(y_pseudo) > 0:
                    print(f'   Normal samples: {np.sum(y_pseudo == 0):,} ({np.sum(y_pseudo == 0) / len(y_pseudo) * 100:.1f}%)')
                    print(f'   Attack samples: {np.sum(y_pseudo == 1):,} ({np.sum(y_pseudo == 1) / len(y_pseudo) * 100:.1f}%)')
                    print(f'   Mean confidence: {np.mean(confidences_pseudo):.3f}')
            return (X_pseudo, y_pseudo, confidences_pseudo, explanations)
        else:
            if self.config.VERBOSE:
                print('No pseudo-labels passed the quality filters.')
            return (None, None, None, explanations)

    def update_xgboost_model(self, X_pseudo, y_pseudo):
        """Retrain XGBoost using the cached data and selected pseudo-labels."""
        if X_pseudo is None or len(X_pseudo) == 0:
            if self.config.VERBOSE:
                print('No pseudo-labeled data; skipping the model update.')
            return False
        if self.config.VERBOSE:
            print(f'\nUpdating XGBoost v{self.model_version:.1f} to v{self.model_version + 0.1:.1f}')
        # Retrain a new classifier using initial data and accepted pseudo-labels.
        X_combined = np.vstack([self.training_data_cache['X'], X_pseudo])
        y_combined = np.hstack([self.training_data_cache['y'], y_pseudo])
        if len(X_combined) > self.max_training_samples:
            if self.config.VERBOSE:
                print(f'Training data exceeds the limit ({len(X_combined):,}); sampling {self.max_training_samples:,} samples')
            try:
                from sklearn.utils import resample
                X_sampled, y_sampled = resample(X_combined, y_combined, n_samples=self.max_training_samples, stratify=y_combined, random_state=self.random_seed)
            except ValueError:
                indices = np.random.choice(len(X_combined), self.max_training_samples, replace=False)
                X_sampled, y_sampled = (X_combined[indices], y_combined[indices])
            X_combined, y_combined = (X_sampled, y_sampled)
        new_model = xgb.XGBClassifier(n_estimators=self.config.XGBOOST_UPDATE_N_ESTIMATORS, max_depth=self.config.XGBOOST_UPDATE_MAX_DEPTH, learning_rate=self.config.XGBOOST_UPDATE_LEARNING_RATE, n_jobs=self.config.XGBOOST_N_JOBS, random_state=self.random_seed, eval_metric='logloss', use_label_encoder=False, reg_alpha=self.config.XGBOOST_UPDATE_REG_ALPHA, reg_lambda=self.config.XGBOOST_UPDATE_REG_LAMBDA, subsample=self.config.XGBOOST_UPDATE_SUBSAMPLE, colsample_bytree=self.config.XGBOOST_UPDATE_COLSAMPLE)
        start_time = time.time()
        new_model.fit(X_combined, y_combined)
        training_time = time.time() - start_time
        if self.config.VERBOSE:
            print(f'Updated model trained; elapsed: {training_time:.2f} seconds')
            print(f'Model parameters: {self.config.XGBOOST_UPDATE_N_ESTIMATORS} trees, depth {self.config.XGBOOST_UPDATE_MAX_DEPTH}')
        self.xgboost_current = new_model
        self.model_version += 0.1
        self.training_data_cache['X'] = X_combined.copy()
        self.training_data_cache['y'] = y_combined.copy()
        if self.config.VERBOSE:
            print(f'Current model is now v{self.model_version:.1f}')
            print(f'Current training set size: {len(X_combined):,} samples')
        return True

    def continuous_learning_step(self, X_unlabeled, y_unlabeled_true=None, X_test=None, y_test=None):
        """Generate pseudo-labels, update the model, and record evaluation results."""
        if self.config.VERBOSE:
            print(f'\n' + '=' * 60)
            print(f'Learning step for current model v{self.model_version:.1f}')
            print('=' * 60)
        X_pseudo, y_pseudo, confidences, explanations = self.generate_pseudo_labels(X_unlabeled, y_true=y_unlabeled_true)
        if X_pseudo is not None and len(X_pseudo) > 0:
            self.pseudo_label_history.append({'step': len(self.performance_history), 'count': len(X_pseudo), 'normal_count': np.sum(y_pseudo == 0), 'attack_count': np.sum(y_pseudo == 1), 'avg_confidence': np.mean(confidences) if confidences is not None else 0})
        current_performance = None
        if X_test is not None and y_test is not None:
            if self.config.VERBOSE:
                print(f'\nEvaluating current model v{self.model_version:.1f}...')
            current_performance = self.evaluate_model(X_test, y_test, verbose=False)
        model_updated = self.update_xgboost_model(X_pseudo, y_pseudo)
        new_performance = None
        if model_updated and X_test is not None and (y_test is not None):
            if self.config.VERBOSE:
                print(f'\nEvaluating updated model v{self.model_version:.1f}...')
            new_performance = self.evaluate_model(X_test, y_test, verbose=False)
            if current_performance and new_performance:
                f1_improvement = new_performance['f1'] - current_performance['f1']
                acc_improvement = new_performance['accuracy'] - current_performance['accuracy']
                if self.config.VERBOSE:
                    print(f'\nPerformance change:')
                    print(f"   F1: {current_performance['f1']:.4f} to {new_performance['f1']:.4f} ({f1_improvement:+.4f})")
                    print(f"   Accuracy: {current_performance['accuracy']:.4f} to {new_performance['accuracy']:.4f} ({acc_improvement:+.4f})")
                    if f1_improvement >= 0:
                        print('F1 improved or remained unchanged.')
                    else:
                        print('F1 decreased.')
            self.performance_history.append({'step': len(self.performance_history), 'version': self.model_version, 'f1': new_performance['f1'], 'accuracy': new_performance['accuracy'], 'precision': new_performance['precision'], 'recall': new_performance['recall'], 'pseudo_samples': len(X_pseudo) if X_pseudo is not None else 0})
        elif not model_updated and self.performance_history:
            self.performance_history.append(self.performance_history[-1].copy())
            self.performance_history[-1]['step'] = len(self.performance_history) - 1
        return {'model_updated': model_updated, 'pseudo_samples_count': len(X_pseudo) if X_pseudo is not None else 0, 'current_performance': current_performance, 'new_performance': new_performance}

    def evaluate_model(self, X_test, y_test, verbose=True):
        """Evaluate the current model on the supplied labeled test data."""
        if self.xgboost_current is None:
            raise ValueError('The model has not been trained.')
        if hasattr(X_test, 'reset_index'):
            X_test = X_test.reset_index(drop=True)
        if hasattr(y_test, 'reset_index'):
            y_test = y_test.reset_index(drop=True)
        X_scaled = self.scaler.transform(X_test)
        predictions = self.xgboost_current.predict(X_scaled)
        accuracy = accuracy_score(y_test, predictions)
        cm = confusion_matrix(y_test, predictions)
        if verbose and self.config.VERBOSE:
            print(f'\nModel v{self.model_version:.1f} evaluation:')
            print(classification_report(y_test, predictions, target_names=['Normal', 'Attack'], zero_division=0))
        if cm.shape == (2, 2):
            tn, fp, fn, tp = cm.ravel()
            precision = tp / (tp + fp) if tp + fp > 0 else 0
            recall = tp / (tp + fn) if tp + fn > 0 else 0
            f1 = 2 * (precision * recall) / (precision + recall) if precision + recall > 0 else 0
            fpr = fp / (fp + tn) if fp + tn > 0 else 0
        else:
            report = classification_report(y_test, predictions, output_dict=True, zero_division=0)
            precision = report['1']['precision'] if '1' in report else 0
            recall = report['1']['recall'] if '1' in report else 0
            f1 = report['1']['f1-score'] if '1' in report else 0
            fpr = 0
        if verbose and self.config.VERBOSE:
            print(f'\nDetailed metrics:')
            print(f'Accuracy: {accuracy:.4f}')
            print(f'Precision (attack): {precision:.4f}')
            print(f'Recall (attack): {recall:.4f}')
            print(f'F1 (attack): {f1:.4f}')
            print(f'False positive rate: {fpr:.4f}')
        return {'accuracy': accuracy, 'precision': precision, 'recall': recall, 'f1': f1, 'fpr': fpr, 'confusion_matrix': cm}

    def plot_learning_history(self):
        """Plot performance, pseudo-label counts, and model versions in four figures."""
        if not self.performance_history:
            print('No performance history is available for plotting.')
            return
        plt.rcParams['font.weight'] = 'bold'
        plt.rcParams['axes.labelweight'] = 'bold'
        plt.rcParams['axes.titleweight'] = 'bold'
        plt.rcParams['axes.linewidth'] = 1.5
        steps = [h['step'] for h in self.performance_history]
        TITLE_SIZE = 18
        LABEL_SIZE = 15
        TICK_SIZE = 12
        LEGEND_SIZE = 12
        plt.figure(figsize=(11, 6), dpi=100)
        plt.plot(steps, [h['f1'] for h in self.performance_history], 'bo-', label='F1 Score', linewidth=2.5, markersize=8)
        plt.plot(steps, [h['accuracy'] for h in self.performance_history], 'ro-', label='Accuracy', linewidth=2.5, markersize=8)
        plt.title('Accuracy and F1 Score Evolution', fontsize=TITLE_SIZE, pad=15)
        plt.xlabel('Learning Steps', fontsize=LABEL_SIZE)
        plt.ylabel('Performance Metrics', fontsize=LABEL_SIZE)
        plt.xticks(steps, fontsize=TICK_SIZE)
        plt.yticks(fontsize=TICK_SIZE)
        plt.legend(prop={'size': LEGEND_SIZE, 'weight': 'bold'})
        plt.grid(True, alpha=0.3, linestyle='--')
        plt.tight_layout()
        plt.show()
        plt.figure(figsize=(11, 6), dpi=100)
        plt.plot(steps, [h['precision'] for h in self.performance_history], 'go-', label='Precision', linewidth=2.5, markersize=8)
        plt.plot(steps, [h['recall'] for h in self.performance_history], 'mo-', label='Recall', linewidth=2.5, markersize=8)
        plt.title('Precision and Recall Evolution', fontsize=TITLE_SIZE, pad=15)
        plt.xlabel('Learning Steps', fontsize=LABEL_SIZE)
        plt.ylabel('Performance Metrics', fontsize=LABEL_SIZE)
        plt.xticks(steps, fontsize=TICK_SIZE)
        plt.yticks(fontsize=TICK_SIZE)
        plt.legend(prop={'size': LEGEND_SIZE, 'weight': 'bold'})
        plt.grid(True, alpha=0.3, linestyle='--')
        plt.tight_layout()
        plt.show()
        plt.figure(figsize=(11, 6), dpi=100)
        counts = [h['pseudo_samples'] for h in self.performance_history]
        plt.bar(steps, counts, color='skyblue', edgecolor='navy', linewidth=1.2, alpha=0.8, width=0.6)
        plt.title('Pseudo-labels Generated per Step', fontsize=TITLE_SIZE, pad=15)
        plt.xlabel('Learning Steps', fontsize=LABEL_SIZE)
        plt.ylabel('Pseudo-label Count', fontsize=LABEL_SIZE)
        plt.xticks(steps, fontsize=TICK_SIZE)
        plt.yticks(fontsize=TICK_SIZE)
        plt.grid(True, axis='y', alpha=0.3, linestyle='--')
        plt.tight_layout()
        plt.show()
        plt.figure(figsize=(11, 6), dpi=100)
        versions = [h['version'] for h in self.performance_history]
        plt.plot(steps, versions, 'cs-', linewidth=3, markersize=10, markerfacecolor='white', markeredgewidth=2)
        plt.title('Model Version Evolution', fontsize=TITLE_SIZE, pad=15)
        plt.xlabel('Learning Steps', fontsize=LABEL_SIZE)
        plt.ylabel('Model Version', fontsize=LABEL_SIZE)
        plt.xticks(steps, fontsize=TICK_SIZE)
        plt.yticks(fontsize=TICK_SIZE)
        plt.grid(True, alpha=0.3, linestyle='--')
        plt.tight_layout()
        plt.show()

def main():
    """Run the configured research experiment."""
    print('SOM-XGBoost continuous learning for intrusion detection')
    print('=' * 70)
    Config.print_config()
    try:
        system = SOMXGBoostContinuousLearning(Config)
        df = system.load_and_preprocess_data(Config.CSV_FILE_PATH, max_rows=Config.MAX_DATA_ROWS)
        X = df.drop('class_label', axis=1)
        y = df['class_label']
        print(f'\nDataset split (total {len(df):,} rows):')
        print(f'Initial training set: {Config.INITIAL_TRAINING_RATIO * 100:.0f}%')
        print(f'Test set: {Config.TEST_RATIO * 100:.0f}%')
        print(f'Unlabeled stream: {Config.UNLABELED_RATIO * 100:.0f}%')
        remaining_ratio = Config.TEST_RATIO + Config.UNLABELED_RATIO
        X_initial, X_remaining, y_initial, y_remaining = train_test_split(X, y, test_size=remaining_ratio, random_state=Config.RANDOM_SEED, stratify=y)
        test_split_ratio = Config.TEST_RATIO / remaining_ratio
        X_test, X_unlabeled_stream, y_test, y_stream = train_test_split(X_remaining, y_remaining, test_size=1 - test_split_ratio, random_state=123, stratify=y_remaining)
        for dataset in [X_initial, y_initial, X_test, y_test, X_unlabeled_stream]:
            if hasattr(dataset, 'reset_index'):
                dataset.reset_index(drop=True, inplace=True)
        print(f'\nActual split sizes:')
        print(f'Initial training set: {len(X_initial):,} samples')
        print(f'Test set: {len(X_test):,} samples')
        print(f'Unlabeled stream: {len(X_unlabeled_stream):,} samples')
        system.train_initial_models(X_initial, y_initial)
        if Config.VERBOSE:
            print(f'\nInitial model performance (step 0):')
        initial_performance = system.evaluate_model(X_test, y_test)
        system.performance_history.append({'step': 0, 'version': 1.0, 'f1': initial_performance['f1'], 'accuracy': initial_performance['accuracy'], 'precision': initial_performance['precision'], 'recall': initial_performance['recall'], 'pseudo_samples': 0})
        print(f'\n' + '=' * 70)
        print('Starting continuous learning')
        print('=' * 70)
        num_batches = Config.NUM_LEARNING_BATCHES
        if len(X_unlabeled_stream) < num_batches:
            batch_size = len(X_unlabeled_stream)
            num_batches = 1
        else:
            batch_size = len(X_unlabeled_stream) // num_batches
        print(f'Splitting the unlabeled stream into {num_batches} batches, approximately {batch_size:,} samples')
        current_process = psutil.Process(os.getpid()) if psutil is not None else None
        # Normalize process CPU usage by the number of logical cores.
        cpu_count = psutil.cpu_count(logical=True) if psutil is not None else 1
        for i in range(num_batches):
            print(f'\nProcessing unlabeled batch {i + 1}/{num_batches}')
            start_idx = i * batch_size
            end_idx = (i + 1) * batch_size if i < num_batches - 1 else len(X_unlabeled_stream)
            batch_X = X_unlabeled_stream.iloc[start_idx:end_idx]
            batch_y_true = y_stream.iloc[start_idx:end_idx]
            if current_process is not None:
                current_process.cpu_percent(interval=None)
            start_time_mon = time.perf_counter()
            result = system.continuous_learning_step(batch_X, y_unlabeled_true=batch_y_true, X_test=X_test, y_test=y_test)
            end_time_mon = time.perf_counter()
            iteration_time = end_time_mon - start_time_mon
            if psutil is not None and current_process is not None:
                try:
                    raw_cpu = current_process.cpu_percent(interval=None)
                    cpu_usage = f'{raw_cpu:.1f}'
                    normalized_cpu = f'{raw_cpu / cpu_count:.1f}'
                    memory_usage = f'{current_process.memory_info().rss / (1024 * 1024):.2f} MB'
                except Exception:
                    cpu_usage = 'N/A'
                    normalized_cpu = 'N/A'
                    memory_usage = 'N/A'
            else:
                cpu_usage = 'N/A'
                normalized_cpu = 'N/A'
                memory_usage = 'N/A'
            if Config.VERBOSE:
                print(f'Batch results:')
                print(f"   Model updated: {('yes' if result['model_updated'] else 'no')}")
                print(f"   Selected pseudo-labels: {result['pseudo_samples_count']:,}")
                print(f'Batch elapsed time: {iteration_time:.4f} seconds')
                print(f'Process CPU usage (100% per core): {cpu_usage}%')
                print(f'Process CPU usage (relative to all logical cores): {normalized_cpu}%')
                print(f'Process memory: {memory_usage}')
            if Config.BATCH_PROCESSING_DELAY > 0:
                time.sleep(Config.BATCH_PROCESSING_DELAY)
        print(f'\n' + '=' * 70)
        print('Final evaluation')
        print('=' * 70)
        final_performance = system.evaluate_model(X_test, y_test)
        print(f'\nOverall performance change from step 0:')
        print(f"Initial F1: {initial_performance['f1']:.4f}")
        print(f"Final F1: {final_performance['f1']:.4f}")
        f1_improvement = final_performance['f1'] - initial_performance['f1']
        if initial_performance['f1'] > 0:
            print(f"F1 change: {f1_improvement:+.4f} ({f1_improvement / initial_performance['f1'] * 100:+.2f}%)")
        print(f"Initial accuracy: {initial_performance['accuracy']:.4f}")
        print(f"Final accuracy: {final_performance['accuracy']:.4f}")
        acc_improvement = final_performance['accuracy'] - initial_performance['accuracy']
        if initial_performance['accuracy'] > 0:
            print(f"Accuracy change: {acc_improvement:+.4f} ({acc_improvement / initial_performance['accuracy'] * 100:+.2f}%)")
        if Config.PLOT_RESULTS:
            print(f'\nPlotting learning history...')
            system.plot_learning_history()
        if Config.VERBOSE:
            print(f'\nRun statistics:')
            print(f'Current model version: v{system.model_version:.1f}')
            print(f'Total learning steps: {len(system.performance_history) - 1}')
            print(f"Current training set size: {len(system.training_data_cache['X']):,} samples")
            if system.pseudo_label_history:
                total_pseudo = sum((h['count'] for h in system.pseudo_label_history))
                avg_confidence = np.mean([h['avg_confidence'] for h in system.pseudo_label_history if h['count'] > 0])
                print(f'Total selected pseudo-labels: {total_pseudo:,}')
                print(f'Mean pseudo-label confidence: {avg_confidence:.3f}')
        print(f'\nSOM-XGBoost learning run complete.')
    except FileNotFoundError:
        print(f"File not found: '{Config.CSV_FILE_PATH}'")
        print('Please check:')
        print('1. Set the correct path in Config.CSV_FILE_PATH.')
        print('2. Confirm that the file exists.')
        print('3. Confirm that the file is a CSV.')
    except Exception as e:
        print(f'Execution failed: {e}')
        import traceback
        traceback.print_exc()
if __name__ == '__main__':
    main()

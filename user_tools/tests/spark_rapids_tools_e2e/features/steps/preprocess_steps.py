# Copyright (c) 2025, NVIDIA CORPORATION.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import glob
import os
from behave import given, when, then
from spark_rapids_tools.tools.qualx.config import get_config
from spark_rapids_tools.tools.qualx.modifiers.align_sql_id import compute_alignment_from_raw_features
from spark_rapids_tools.tools.qualx.preprocess import (
    load_datasets,
    expected_raw_features
)
from e2e_utils import E2ETestUtils

# Get logger from E2ETestUtils
logger = E2ETestUtils.get_logger()


@given('SPARK_HOME environment variable is set')
def set_spark_home_env(context):
    """Set the SPARK_HOME environment variable using spark home directory."""
    spark_home = E2ETestUtils.get_spark_home()
    os.environ['SPARK_HOME'] = spark_home
    assert 'SPARK_HOME' in os.environ


@given('SPARK_RAPIDS_TOOLS_JAR environment variable is set')
def set_tools_jar_env(context):
    """Set the SPARK_RAPIDS_TOOLS_JAR environment variable using tools jar file."""
    tools_jar = E2ETestUtils.get_tools_jar_file()
    os.environ['SPARK_RAPIDS_TOOLS_JAR'] = tools_jar
    assert 'SPARK_RAPIDS_TOOLS_JAR' in os.environ
    assert os.path.exists(os.environ['SPARK_RAPIDS_TOOLS_JAR'])


@given('QUALX_DATA_DIR environment variable is set')
def set_qualx_data_dir_env(context):
    """Set the QUALX_DATA_DIR environment variable using test resources directory."""
    context.qualx_data_dir = E2ETestUtils.get_local_event_logs_dir()
    os.environ['QUALX_DATA_DIR'] = context.qualx_data_dir
    assert 'QUALX_DATA_DIR' in os.environ


@given('QUALX_CACHE_DIR environment variable is set')
def set_qualx_cache_dir_env(context):
    """Set the QUALX_CACHE_DIR environment variable using test resources directory."""
    context.qualx_cache_dir = os.path.join(E2ETestUtils.get_e2e_tests_resource_path(), 'qualx_cache')
    os.environ['QUALX_CACHE_DIR'] = context.qualx_cache_dir
    assert 'QUALX_CACHE_DIR' in os.environ


@given('QUALX_LABEL environment variable is set to "{label}"')
def step_impl(context, label):
    """Set the QUALX_LABEL environment variable to the specified value."""
    context.qualx_label = label
    os.environ['QUALX_LABEL'] = label
    get_config(reload=True)


@given('environment variable "{variable_name}" is set to "{value}"')
def set_environment_variable(context, variable_name, value):
    """Set the specified RAPIDS_USER_TOOLS_* environment variable to the given value."""
    os.environ[variable_name] = value


@given('sample event logs in the QUALX_DATA_DIR')
def check_sample_event_logs(context):
    """Verify sample event logs exist in test resources."""
    assert os.path.exists(context.qualx_data_dir)
    event_logs = glob.glob(os.path.join(context.qualx_data_dir, '**', '*.zstd'), recursive=True)
    assert len(event_logs) > 0, 'No event logs found in the QUALX_DATA_DIR'


@given('dataset JSON files in the datasets directory')
def check_dataset_json(context):
    """Verify dataset JSON file exists in test resources."""
    context.dataset_path = os.path.join(E2ETestUtils.get_e2e_tests_resource_path(), 'datasets')
    dataset_json = glob.glob(os.path.join(context.dataset_path, '**', '*.json'), recursive=True)
    assert len(dataset_json) > 0, 'No dataset JSON files found in the datasets directory'


@when('preprocessing the event logs')
def load_and_preprocess_logs(context):
    """Load and preprocess the event logs."""
    try:
        context.datasets, context.profile_df = load_datasets(context.dataset_path)
        context.preprocessing_success = True
    except Exception as e:
        context.preprocessing_success = False
        context.preprocessing_error = str(e)


@then('preprocessing should complete successfully')
def verify_preprocessing_success(context):
    """Verify that preprocessing completed without errors."""
    assert context.preprocessing_success, \
        f"Preprocessing failed with error: {getattr(context, 'preprocessing_error', 'Unknown error')}"
    assert context.datasets is not None, 'Datasets dictionary should not be None'
    assert not context.profile_df.empty, 'Profile DataFrame should not be empty'
    assert len(context.profile_df) == 194, 'Profile DataFrame should have 194 rows'


@then('preprocessed data should contain the expected features for label "{label}"')
def verify_expected_features(context, label):
    """Verify that the preprocessed data contains all expected features for the given label."""
    actual_features = set(context.profile_df.columns)
    expected_features = expected_raw_features()
    missing_features = expected_features - actual_features
    extra_features = actual_features - expected_features

    assert label in actual_features, f'Label {label} should be in the expected features'
    assert not missing_features, f'Missing expected features: {missing_features} (try removing preprocessed.parquet)'
    assert not extra_features, f'Found unexpected features: {extra_features} (try removing preprocessed.parquet)'

    assert len(context.profile_df) == 194, 'Profile DataFrame should have 194 rows'


@then('sqlID hashes should align between CPU and GPU runs')
def verify_sql_id_alignment(context):
    """Verify that SQL IDs are properly aligned between CPU and GPU runs."""
    df = context.profile_df.copy()

    assert 'hash' in df.columns, 'hash column should be in the DataFrame'
    assert df['hash'].notna().all(), 'hash column should not contain any NaN values'

    alignment_df = compute_alignment_from_raw_features(df)
    assert not alignment_df.empty, 'Alignment DataFrame should not be empty'

    # these are specific to the test eventlogs
    assert len(alignment_df) >= 46, 'Alignment DataFrame should have at least 46 rows'
    assert all(alignment_df.sqlID_cpu == alignment_df.sqlID_gpu), 'sqlID_cpu should match sqlID_gpu'

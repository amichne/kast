#[path = "../../support/mod.rs"]
mod support;

use base64::{Engine as _, engine::general_purpose::STANDARD as STANDARD_BASE64};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};
use support::{
    api_schema_version, kast_at, scripted_json_rpc_error,
    scripted_json_rpc_error_with_retained_artifact, spawn_gated_foreign_prepared_scratch_backend,
    spawn_gated_mutating_indexer_backend_with_file_write,
    spawn_gated_prepared_scratch_crash_backend, spawn_gated_quarantine_scratch_crash_backend,
    spawn_lease_only_mutating_indexer_backend, spawn_scripted_indexer_backend,
    spawn_scripted_indexer_backend_for_invocations, spawn_scripted_mutating_indexer_backend,
    spawn_scripted_mutating_indexer_backend_with_file_write, workspace_database_path_for_test,
    workspace_files::WorkspaceIndexFixture, write_active_kast_for_test,
};

#[path = "kast_public_operations/support.rs"]
mod operation_support;
use operation_support::*;

#[path = "kast_public_operations/plans/addition_and_refresh.rs"]
mod addition_and_refresh;
#[path = "kast_public_operations/apply/independent_diagnostics_tests.rs"]
mod independent_diagnostics_tests;
#[path = "kast_public_operations/apply/journal_durability.rs"]
mod journal_durability;
#[path = "kast_public_operations/recovery/postwrite_recovery.rs"]
mod postwrite_recovery;
#[path = "kast_public_operations/recovery/prepared_recovery.rs"]
mod prepared_recovery;
#[path = "kast_public_operations/apply/public_basics.rs"]
mod public_basics;
#[path = "kast_public_operations/plans/rename_plan.rs"]
mod rename_plan;
#[path = "kast_public_operations/plans/replacement_plan.rs"]
mod replacement_plan;
#[path = "kast_public_operations/recovery/retained_artifacts.rs"]
mod retained_artifacts;
#[path = "kast_public_operations/recovery/scratch_recovery.rs"]
mod scratch_recovery;
#[path = "kast_public_operations/apply/terminal_state.rs"]
mod terminal_state;

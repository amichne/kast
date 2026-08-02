use crate::agent_adapter;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentMutationApplyArgs, AgentPlacementAnchor, AgentRenameArgs,
    AgentReplaceDeclarationArgs, AgentScopedMutationArgs, AgentStatementAnchor,
    AgentStatementMutationArgs, AgentWorkspaceLeaseId, KastChangeArgs, KastChangeCommand,
};
use crate::error::{CliError, Result};
use crate::{config, manifest, output};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fs::{self, File, OpenOptions};
use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use uuid::{Uuid, Version};

const PLAN_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredPlan {
    schema_version: u32,
    plan_id: Uuid,
    workspace_root: String,
    operation: StoredOperation,
    content_sha256: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(
    tag = "operation",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredOperation {
    Rename { symbol: String, new_name: String },
    AddFile { path: PathBuf },
    AddDeclaration { path: PathBuf },
    AddImplementation { scope: String },
    AddStatement { scope: String },
    Replace { symbol: String },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChangeResult {
    plan_id: String,
    operation: &'static str,
    plan: Value,
    next: String,
}

include!("execution.rs");
include!("operation.rs");
include!("storage.rs");

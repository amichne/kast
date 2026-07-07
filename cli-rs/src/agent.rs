#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::{
    AgentCallArgs, AgentCommand, AgentDiagnosticsArgs, AgentDiscoverArgs, AgentFileOutlineArgs,
    AgentFilePathsArgs, AgentImpactArgs, AgentMetricsArgs, AgentOptionalFilePathsArgs,
    AgentPositionArgs, AgentRawCallHierarchyArgs, AgentRawCodeActionsArgs, AgentRawCompletionsArgs,
    AgentRawImplementationsArgs, AgentRawReferencesArgs, AgentRawRenameArgs, AgentRawResolveArgs,
    AgentRawSemanticInsertionPointArgs, AgentRawTypeHierarchyArgs, AgentRenameArgs,
    AgentRuntimeArgs, AgentScaffoldArgs, AgentSymbolArgs, AgentSymbolCallersArgs,
    AgentSymbolReferencesArgs, AgentSymbolResolveArgs, AgentToolsArgs, AgentVerifyArgs,
    AgentWorkflowCommand, AgentWorkflowCommonArgs, AgentWorkflowDiagnosticsArgs,
    AgentWorkflowPackageVerifyArgs, AgentWorkflowSymbolArgs, AgentWorkflowWriteMode,
    AgentWorkflowWriteValidateArgs, AgentWorkspaceFilesArgs, AgentWorkspaceSearchArgs,
    AgentWorkspaceSymbolArgs,
};
use crate::cli::{OutputFormat, ReadyTarget};
use crate::error::{CliError, Result};
use crate::{catalog_schema, config, manifest, output, runtime, self_mgmt, validate};
use serde::Serialize;
use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, IsTerminal, Read};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const TOOL_CATEGORY_ORDER: &[&str] = &["symbol", "database", "system", "raw"];

include!("agent/types.rs");
include!("agent/dispatch.rs");
include!("agent/tools.rs");
include!("agent/request.rs");
include!("agent/workflow.rs");
include!("agent/package_verify.rs");
include!("agent/envelope.rs");
include!("agent/input.rs");
include!("agent/response.rs");
include!("agent/aliases.rs");
include!("agent/tests.rs");

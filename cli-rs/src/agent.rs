#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentCommand, AgentDiagnosticsArgs, AgentDiscoverArgs, AgentFileOutlineArgs,
    AgentFilePathsArgs, AgentImpactArgs, AgentMetricsArgs, AgentOptionalFilePathsArgs,
    AgentPositionArgs, AgentRawCallHierarchyArgs, AgentRawCodeActionsArgs, AgentRawCompletionsArgs,
    AgentRawImplementationsArgs, AgentRawReferencesArgs, AgentRawRenameArgs, AgentRawResolveArgs,
    AgentRawSemanticInsertionPointArgs, AgentRawTypeHierarchyArgs, AgentRenameArgs,
    AgentRuntimeArgs, AgentScaffoldArgs, AgentSymbolArgs, AgentSymbolCallersArgs,
    AgentSymbolReferencesArgs, AgentSymbolResolveArgs, AgentVerifyArgs, AgentWorkspaceFilesArgs,
    AgentWorkspaceSearchArgs, AgentWorkspaceSymbolArgs,
};
use crate::error::{CliError, Result};
use crate::{output, runtime, validate};
use serde::Serialize;
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;

include!("agent/types.rs");
include!("agent/dispatch.rs");
include!("agent/request.rs");
include!("agent/envelope.rs");
include!("agent/input.rs");
include!("agent/response.rs");
include!("agent/aliases.rs");
include!("agent/tests.rs");

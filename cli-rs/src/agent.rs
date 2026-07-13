#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentDiagnosticsArgs, AgentImpactArgs, AgentRenameArgs,
    AgentReplaceDeclarationArgs, AgentRuntimeArgs, AgentScopedMutationArgs,
    AgentStatementMutationArgs, AgentSymbolArgs, AgentVerifyArgs,
};
use crate::error::{CliError, Result};
use crate::{output, runtime, validate};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;

include!("agent/types.rs");
include!("agent/dispatch.rs");
include!("agent/request.rs");
include!("agent/envelope.rs");
include!("agent/input.rs");
include!("agent/response.rs");

#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentCommand, AgentDiagnosticsArgs, AgentImpactArgs, AgentRenameArgs, AgentRuntimeArgs,
    AgentSymbolArgs, AgentVerifyArgs,
};
use crate::error::{CliError, Result};
use crate::{output, runtime, validate};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::BTreeMap;

include!("agent/types.rs");
include!("agent/dispatch.rs");
include!("agent/request.rs");
include!("agent/envelope.rs");
include!("agent/input.rs");
include!("agent/response.rs");

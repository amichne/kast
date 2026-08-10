use crate::agent;
use crate::cli::{
    AgentCommand, AgentDiagnosticsArgs, AgentDiagnosticsViewArgs, AgentNativeGraphArgs,
    AgentRuntimeArgs, AgentWorkspaceFilesArgs, AgentWorkspaceFilesField,
    AgentWorkspaceFilesViewArgs, KastDiagnosticArgs, KastDiagnosticCommand, KastFileArgs,
    KastFileCommand, KastGraphArgs, KastGraphCommand, KastGraphProjectionArgs, KastGraphScope,
    KastRelationArgs, KastRelationCallsCommand, KastRelationCommand, KastRelationHierarchyCommand,
    KastSymbolArgs, KastSymbolCommand, KastWorkspaceArgs, KastWorkspaceCommand,
    NativeGraphOperation, NativeGraphScope, OutputFormat, WorkspaceDirtyFilter,
    WorkspaceRelativeGlob,
};
use crate::error::{CliError, Result};
#[cfg(test)]
use crate::runtime::{RuntimeState, RuntimeStatusResponse};
use crate::{config, output, runtime};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

include!("commands.rs");
include!("check.rs");
include!("refresh.rs");
include!("graph.rs");
include!("discovery.rs");
include!("output.rs");
include!("external.rs");
include!("support.rs");
include!("tests.rs");

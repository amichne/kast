use crate::agent;
use crate::cli::{
    AgentCallsArgs, AgentCommand, AgentDiagnosticsArgs, AgentDiagnosticsViewArgs,
    AgentHierarchyArgs, AgentHierarchyDirection, AgentImpactArgs, AgentImpactViewArgs,
    AgentImplementationsArgs, AgentNativeGraphArgs, AgentRelationDepth, AgentRelationLimit,
    AgentRelationViewArgs, AgentReusableSymbolSelectorArgs, AgentRuntimeArgs, AgentSelectorHandle,
    AgentSymbolArgs, AgentSymbolMode, AgentSymbolViewArgs, AgentWorkspaceFilesArgs,
    AgentWorkspaceFilesField, AgentWorkspaceFilesViewArgs, KastGraphArgs, KastGraphCommand,
    KastGraphNodesPageToken, KastGraphProjectionArgs, KastGraphScope, KastPathsArgs,
    KastRefreshArgs, KastRefreshCommand, KastRelationArgs, KastRelationCommand, KastSymbolArgs,
    KastSymbolCommand, NativeGraphOperation, NativeGraphScope, OutputFormat, WorkspaceDirtyFilter,
    WorkspaceFilesPublicPageToken, WorkspaceRelativeGlob,
};
use crate::error::{CliError, Result};
use crate::runtime::{RuntimeState, RuntimeStatusResponse};
use crate::{config, output, runtime};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

include!("commands.rs");
include!("check.rs");
include!("refresh.rs");
include!("graph.rs");
include!("discovery.rs");
include!("output.rs");
include!("external.rs");
include!("support.rs");
include!("tests.rs");

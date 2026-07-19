#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentAddFileArgs, AgentCallsArgs, AgentCommand, AgentDiagnosticsArgs, AgentDiagnosticsField,
    AgentDiagnosticsViewArgs, AgentExactSymbolSelectorArgs, AgentHierarchyArgs,
    AgentHierarchyDirection, AgentImpactArgs, AgentImpactField, AgentImpactPageToken,
    AgentImpactViewArgs, AgentImplementationsArgs, AgentLeaseArgs, AgentLeaseCommand,
    AgentMutationApplyArgs, AgentMutationField, AgentMutationViewArgs, AgentOperationArgs,
    AgentOperationCommand, AgentOperationSelectorArgs, AgentReferencesArgs, AgentRelationField,
    AgentRelationPageToken, AgentRelationViewArgs, AgentRenameArgs, AgentReplaceDeclarationArgs,
    AgentReusableSymbolSelector, AgentReusableSymbolSelectorArgs, AgentRuntimeArgs,
    AgentScopedMutationArgs, AgentSelectorHandle, AgentStatementMutationArgs, AgentSymbolArgs,
    AgentSymbolField, AgentSymbolMode, AgentSymbolViewArgs, AgentTaskArgs, AgentTaskCommand,
    AgentTaskWorkspaceArgs, AgentVerifyArgs, AgentVerifyField, AgentVerifyViewArgs,
    AgentWorkspaceFilesArgs, AgentWorkspaceFilesField, AgentWorkspaceFilesViewArgs, BackendName,
    WorkspaceDirtyFilter, WorkspaceDriftFilter, WorkspaceFileKindFilter,
    WorkspaceFilesPublicPageToken, WorkspaceModuleSelector, WorkspacePackageSelector,
    WorkspaceRelativeGlob, WorkspaceRelativePathPrefix, WorkspaceSourceSetName,
};
use crate::error::{CliError, Result};
use crate::metrics_database::ImpactSubjectKind;
use crate::workspace_inventory::backend::{
    BackendRpcFailure, BackendWorkspaceRpc, RawRpcWorkspaceBackend,
};
use crate::workspace_inventory::collect::{
    SystemWorkspaceLaneReader, WorkspaceInventoryInputs, collect_workspace_inventory,
};
use crate::workspace_inventory::model::{
    BackendModuleCoverage, BackendWorkspaceCoverage, WorkspaceCoverageDimension,
    WorkspaceEvidenceSource, WorkspaceFileDirtyState, WorkspaceFileDrift, WorkspaceFileIndexState,
    WorkspaceFileKind, WorkspaceInventoryFile, WorkspaceInventoryLimitationCode,
    WorkspaceKindMatchCoverage, WorkspacePackageEvidence, WorkspaceRequestedKindDomain,
    WorkspaceRoot, WorkspaceSourceSetEvidence,
};
use crate::{output, runtime, validate};
use clap::CommandFactory;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Component, Path, PathBuf};

const AGENT_TASK_GUIDANCE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/agent-task/guidance.md"
));

pub(crate) fn agent_task_guidance() -> &'static str {
    AGENT_TASK_GUIDANCE.trim()
}

include!("agent/types.rs");
include!("agent/path.rs");
include!("agent/public_capabilities.rs");
include!("agent/task.rs");
include!("agent/workspace_files.rs");
include!("agent/relations.rs");
include!("agent/dispatch.rs");
include!("agent/request.rs");
include!("agent/envelope.rs");
include!("agent/projection.rs");
include!("agent/input.rs");
include!("agent/response.rs");
include!("agent/symbol_lookup.rs");

#[cfg(test)]
mod semantic_analysis_evidence_tests {
    use super::*;

    #[test]
    fn normalized_requested_file_path_matches_normalized_status_path() {
        let request = json!({
            "params": {
                "filePaths": ["/workspace/src/../src/Sample.kt"],
                "maxResults": 8
            }
        });
        let result = json!({
            "diagnostics": [],
            "fileStatuses": [{
                "filePath": "/workspace/src/Sample.kt",
                "state": "ANALYZED"
            }],
            "fileHashes": [{
                "filePath": "/workspace/src/Sample.kt",
                "hash": "a".repeat(64)
            }],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 1,
            "analyzedFileCount": 1,
            "skippedFileCount": 0,
            "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
            "cardinality": {"type": "EXACT", "totalCount": 0}
        });

        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result("raw/diagnostics", &request, Some(&result),),
            AgentSemanticAnalysisEvidence::Valid(_),
        ));
    }

    #[test]
    fn diagnostics_require_ordered_current_hash_evidence_for_analyzed_files() {
        let request = json!({
            "params": {
                "filePaths": ["/workspace/A.kt", "/workspace/B.kt"],
                "maxResults": 8
            }
        });
        let mut result = json!({
            "diagnostics": [],
            "fileStatuses": [
                {"filePath": "/workspace/A.kt", "state": "ANALYZED"},
                {"filePath": "/workspace/B.kt", "state": "ANALYZED"}
            ],
            "fileHashes": [
                {"filePath": "/workspace/B.kt", "hash": "b".repeat(64)},
                {"filePath": "/workspace/A.kt", "hash": "a".repeat(64)}
            ],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 2,
            "analyzedFileCount": 2,
            "skippedFileCount": 0,
            "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
            "cardinality": {"type": "EXACT", "totalCount": 0}
        });

        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result("raw/diagnostics", &request, Some(&result)),
            AgentSemanticAnalysisEvidence::Invalid,
        ));

        result["fileHashes"] = json!([
            {"filePath": "/workspace/A.kt", "hash": "not-a-sha-256-digest"},
            {"filePath": "/workspace/B.kt", "hash": "b".repeat(64)}
        ]);
        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result("raw/diagnostics", &request, Some(&result)),
            AgentSemanticAnalysisEvidence::Invalid,
        ));
    }

    #[test]
    fn unrelated_command_result_does_not_require_diagnostics_evidence() {
        let request = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "runtime/status",
            "params": {}
        });
        let result = json!({
            "semanticOutcome": "not a diagnostics outcome",
            "schemaVersion": 3
        });

        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result("runtime/status", &request, Some(&result),),
            AgentSemanticAnalysisEvidence::NotDiagnostics,
        ));
    }

    #[test]
    fn full_workspace_refresh_requires_the_complete_admission_contract() {
        let request = json!({"params": {"filePaths": []}});
        let mut result = json!({
            "refreshedFiles": [],
            "removedFiles": [],
            "fullRefresh": true,
            "fileStatuses": [],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 0,
            "analyzedFileCount": 0,
            "skippedFileCount": 0,
            "removedFileCount": 0,
            "attemptCount": 1,
            "elapsedMillis": 0,
            "schemaVersion": 3
        });

        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result(
                "raw/workspace-refresh",
                &request,
                Some(&result),
            ),
            AgentSemanticAnalysisEvidence::Valid(_),
        ));

        result.as_object_mut().unwrap().remove("attemptCount");
        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result(
                "raw/workspace-refresh",
                &request,
                Some(&result),
            ),
            AgentSemanticAnalysisEvidence::Invalid,
        ));
    }
}

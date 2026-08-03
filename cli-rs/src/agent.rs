use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentAddFileArgs, AgentCallsArgs, AgentCommand, AgentDiagnosticsArgs, AgentDiagnosticsField,
    AgentDiagnosticsViewArgs, AgentExactSymbolSelectorArgs, AgentHierarchyArgs,
    AgentHierarchyDirection, AgentImpactArgs, AgentImpactField, AgentImpactPageToken,
    AgentImpactViewArgs, AgentImplementationsArgs, AgentLeaseArgs, AgentLeaseCommand,
    AgentMutationApplyArgs, AgentMutationField, AgentMutationViewArgs, AgentNativeGraphArgs,
    AgentPlacementAnchor, AgentReferencesArgs, AgentRelationField, AgentRelationPageToken,
    AgentRelationViewArgs, AgentRenameArgs, AgentReplaceDeclarationArgs, AgentRepositoryArgs,
    AgentRepositoryField, AgentRepositoryIntent, AgentRepositoryViewArgs,
    AgentReusableSymbolSelector, AgentReusableSymbolSelectorArgs, AgentRuntimeArgs,
    AgentScopedMutationArgs, AgentSelectorHandle, AgentStatementMutationArgs, AgentSymbolArgs,
    AgentSymbolField, AgentSymbolMode, AgentSymbolViewArgs, AgentVerifyArgs, AgentVerifyField,
    AgentVerifyViewArgs, AgentWorkspaceFilesArgs, AgentWorkspaceFilesField,
    AgentWorkspaceFilesViewArgs, NativeGraphOperation, NativeGraphScope, WorkspaceDirtyFilter,
    WorkspaceDriftFilter, WorkspaceFileKindFilter, WorkspaceFilesPublicPageToken,
    WorkspaceModuleSelector, WorkspacePackageSelector, WorkspaceRelativeGlob,
    WorkspaceRelativePathPrefix, WorkspaceSourceSetName,
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
    BackendModuleCoverage, BackendWorkspaceCoverage, BuildQualifiedGradleProjectIdentity,
    BuildQualifiedGradleSourceSetIdentity, WorkspaceCoverageDimension, WorkspaceEvidenceSource,
    WorkspaceFileDirtyState, WorkspaceFileDrift, WorkspaceFileIndexState, WorkspaceFileKind,
    WorkspaceInventoryFile, WorkspaceInventoryLimitationCode, WorkspaceKindMatchCoverage,
    WorkspacePackageEvidence, WorkspaceRequestedKindDomain, WorkspaceRoot,
    WorkspaceSourceSetEvidence,
};
use crate::{output, runtime, validate};
use clap::CommandFactory;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Component, Path, PathBuf};

include!("agent/core/types/mod.rs");
include!("agent/core/path/mod.rs");
include!("agent/core/public_capabilities.rs");
include!("agent/workspace_files.rs");
include!("agent/navigation/native_graph.rs");
include!("agent/navigation/relations.rs");
include!("agent/core/dispatch/mod.rs");
include!("agent/core/request.rs");
include!("agent/core/envelope.rs");
include!("agent/projection.rs");
include!("agent/core/input.rs");
include!("agent/core/response.rs");
include!("agent/core/symbol_lookup/mod.rs");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LeasedRawOperation {
    ReadOnly,
    ExactFileImageCas,
    FileOperation,
    ScratchRecovery,
}

pub(crate) const BACKEND_RECOVERY_DETAILS_INVALID: &str = "__kastBackendRecoveryDetailsInvalid";

pub(crate) fn execute_leased_raw_value(
    workspace_root: &Path,
    lease_id: crate::cli::AgentWorkspaceLeaseId,
    method: &str,
    params: Value,
    operation: LeasedRawOperation,
) -> Result<Value> {
    let method_is_allowed = match operation {
        LeasedRawOperation::ReadOnly => matches!(
            method,
            "raw/rename"
                | "raw/plan-replacement"
                | "raw/plan-add-file"
                | "raw/plan-add-declaration"
                | "raw/exact-file-observation"
                | "raw/workspace-refresh"
                | "raw/diagnostics"
                | "raw/verify-mutation-postcondition"
        ),
        LeasedRawOperation::ExactFileImageCas => method == "raw/exact-file-image-cas",
        LeasedRawOperation::FileOperation => method == "raw/apply-edits",
        LeasedRawOperation::ScratchRecovery => matches!(
            method,
            "raw/inspect-mutation-scratch" | "raw/recover-mutation-scratch"
        ),
    };
    if !method_is_allowed {
        return Err(CliError::new(
            "KAST_RAW_MUTATION_METHOD_INVALID",
            "The typed leased operation does not authorize this raw method.",
        ));
    }
    let validated_lease =
        runtime::validate_workspace_lease_for_command(&lease_id, Some(workspace_root))?;
    let admission =
        match runtime::semantic_mutation_workspace_route(Some(workspace_root.to_path_buf()))? {
            runtime::SemanticWorkspaceRoute::Admitted(admission) => admission,
            runtime::SemanticWorkspaceRoute::Rejected(rejection) => {
                return Err(CliError::new(rejection.code, rejection.message));
            }
        };
    if !validated_lease.authorizes(&admission) {
        return Err(CliError::new(
            "WORKSPACE_LEASE_RUNTIME_REPLACED",
            "The admitted indexer is not the exact runtime authenticated by the workspace lease.",
        ));
    }
    let required_capabilities: &[runtime::SemanticMutationCapability] = match (operation, method) {
        (LeasedRawOperation::ReadOnly, "raw/rename") => {
            &[runtime::SemanticMutationCapability::Rename]
        }
        (LeasedRawOperation::ReadOnly, "raw/plan-replacement") => {
            &[runtime::SemanticMutationCapability::PlanReplacement]
        }
        (LeasedRawOperation::ReadOnly, "raw/plan-add-file") => {
            &[runtime::SemanticMutationCapability::PlanAddFile]
        }
        (LeasedRawOperation::ReadOnly, "raw/plan-add-declaration") => {
            &[runtime::SemanticMutationCapability::PlanAddDeclaration]
        }
        (LeasedRawOperation::ReadOnly, "raw/exact-file-observation") => {
            &[runtime::SemanticMutationCapability::ExactFileObservation]
        }
        (LeasedRawOperation::ReadOnly, "raw/workspace-refresh") => {
            &[runtime::SemanticMutationCapability::RefreshWorkspace]
        }
        (LeasedRawOperation::ReadOnly, "raw/verify-mutation-postcondition") => {
            &[runtime::SemanticMutationCapability::VerifyMutationPostcondition]
        }
        (LeasedRawOperation::ReadOnly, "raw/diagnostics") => &[],
        (LeasedRawOperation::ExactFileImageCas, "raw/exact-file-image-cas") => {
            &[runtime::SemanticMutationCapability::ExactFileImageCas]
        }
        (LeasedRawOperation::FileOperation, "raw/apply-edits") => &[
            runtime::SemanticMutationCapability::ApplyEdits,
            runtime::SemanticMutationCapability::FileOperations,
        ],
        (
            LeasedRawOperation::ScratchRecovery,
            "raw/inspect-mutation-scratch" | "raw/recover-mutation-scratch",
        ) => &[runtime::SemanticMutationCapability::MutationScratchRecovery],
        _ => unreachable!("method and operation pair was validated above"),
    };
    if required_capabilities
        .iter()
        .any(|capability| !admission.supports_mutation(*capability))
    {
        return Err(CliError::new(
            "SEMANTIC_MUTATION_CAPABILITY_UNAVAILABLE",
            "The admitted indexer did not advertise every required mutation-session capability.",
        ));
    }
    let session = runtime::raw_rpc_session_for_admission(*admission);
    let envelope = execute_request_with_session(
        AgentRequest {
            method: method.to_string(),
            request: json_rpc_request(method, params),
            runtime: AgentRuntimeArgs {
                workspace_root: Some(workspace_root.to_path_buf()),
                lease_id: Some(lease_id),
            },
            full_response: true,
            operation: AgentOperation::MutationPreview,
        },
        Some(&session),
    );
    if !envelope.ok {
        let error = envelope.error.unwrap_or_else(|| {
            agent_error(
                "KAST_RAW_MUTATION_SESSION_FAILED",
                "The leased raw operation failed without a typed error.",
            )
        });
        let recovery_details = closed_backend_recovery_details(&error);
        let mut cli_error = match error.code.as_str() {
            "MUTATION_PROOF_INCOMPLETE"
            | "REPLACEMENT_PROOF_INCOMPLETE"
            | "ADDITION_PROOF_INCOMPLETE" => {
                CliError::new("KAST_MUTATION_REVALIDATION_REJECTED", error.message)
            }
            "SEMANTIC_ANALYSIS_INCOMPLETE" | "SEMANTIC_ANALYSIS_INVALID"
                if matches!(method, "raw/workspace-refresh" | "raw/diagnostics") =>
            {
                CliError::new("KAST_COMPILER_VERIFICATION_INVALID", error.message)
            }
            "MUTATION_POSTCONDITION_FAILED" => {
                CliError::new("KAST_MUTATION_POSTCONDITION_FAILED", error.message)
            }
            _ => CliError::new(
                "KAST_RAW_MUTATION_SESSION_FAILED",
                format!("{}: {}", error.code, error.message),
            ),
        };
        cli_error.details = recovery_details;
        return Err(cli_error);
    }
    envelope.result.ok_or_else(|| {
        CliError::new(
            "KAST_RAW_MUTATION_SESSION_INVALID",
            "The leased raw operation returned no typed result.",
        )
    })
}

fn closed_backend_recovery_details(error: &AgentError) -> BTreeMap<String, String> {
    let Some(details) = error
        .details
        .get("rpcError")
        .and_then(|rpc_error| rpc_error.pointer("/data/details"))
        .and_then(Value::as_object)
    else {
        return BTreeMap::new();
    };
    let recovery_details = details
        .iter()
        .filter(|(key, _)| key.starts_with("recoveryFilePath"))
        .collect::<Vec<_>>();
    if recovery_details.is_empty() {
        return BTreeMap::new();
    }
    let mut closed = BTreeMap::new();
    for (key, value) in recovery_details {
        let indexed = key.strip_prefix("recoveryFilePath.").is_some_and(|index| {
            !index.is_empty() && index.bytes().all(|byte| byte.is_ascii_digit())
        });
        let key_is_closed = key == "recoveryFilePathCount" || indexed;
        let Some(value) = value.as_str() else {
            closed.insert(
                BACKEND_RECOVERY_DETAILS_INVALID.to_string(),
                "A backend recovery detail was not a string.".to_string(),
            );
            return closed;
        };
        if !key_is_closed {
            closed.insert(
                BACKEND_RECOVERY_DETAILS_INVALID.to_string(),
                "The backend recovery details used an unknown field.".to_string(),
            );
            return closed;
        }
        closed.insert(key.clone(), value.to_string());
    }
    closed
}

pub(crate) fn normalize_public_file_paths(
    runtime: &AgentRuntimeArgs,
    file_paths: &[String],
) -> std::result::Result<Vec<String>, AgentError> {
    AgentFilePathNormalizer::from_runtime(runtime)?.normalize_all(file_paths)
}

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
            "schemaVersion": SCHEMA_VERSION
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
            "schemaVersion": SCHEMA_VERSION
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

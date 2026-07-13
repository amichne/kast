#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentDiagnosticsArgs, AgentImpactArgs, AgentMutationApplyArgs,
    AgentOperationArgs, AgentOperationCommand, AgentOperationSelectorArgs, AgentRenameArgs,
    AgentReplaceDeclarationArgs, AgentRuntimeArgs, AgentScopedMutationArgs,
    AgentStatementMutationArgs, AgentSymbolArgs, AgentSymbolMode, AgentVerifyArgs,
};
use crate::error::{CliError, Result};
use crate::{output, runtime, validate};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};

include!("agent/types.rs");
include!("agent/path.rs");
include!("agent/dispatch.rs");
include!("agent/request.rs");
include!("agent/envelope.rs");
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
                "filePaths": ["/workspace/src/../src/Sample.kt"]
            }
        });
        let result = json!({
            "diagnostics": [],
            "fileStatuses": [{
                "filePath": "/workspace/src/Sample.kt",
                "state": "ANALYZED"
            }],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 1,
            "analyzedFileCount": 1,
            "skippedFileCount": 0
        });

        assert!(matches!(
            AgentSemanticAnalysisEvidence::from_result("raw/diagnostics", &request, Some(&result),),
            AgentSemanticAnalysisEvidence::Valid(_),
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

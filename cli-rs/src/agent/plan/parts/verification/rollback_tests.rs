    use super::*;
    use crate::SCHEMA_VERSION;

    fn transition(preimage: ExactMutationPreimage) -> ExactMutationTransition {
        ExactMutationTransition {
            relative_path: "src/Restored.kt".to_string(),
            absolute_path: "/workspace/src/Restored.kt".to_string(),
            preimage,
            postimage: AgentExactByteImage::from_bytes(b"post"),
        }
    }

    fn diagnostics_page(schema_version: u32) -> ProtocolDiagnosticsEvidence {
        serde_json::from_value(serde_json::json!({
            "diagnostics": [],
            "fileStatuses": [{
                "filePath": "/workspace/src/Checked.kt",
                "state": "ANALYZED"
            }],
            "fileHashes": [{
                "filePath": "/workspace/src/Checked.kt",
                "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            }],
            "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
            "cardinality": {"type": "EXACT", "totalCount": 0},
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 1,
            "analyzedFileCount": 1,
            "skippedFileCount": 0,
            "schemaVersion": schema_version
        }))
        .expect("closed live diagnostics evidence")
    }

    fn validate_diagnostics_schema(schema_version: u32) -> Result<()> {
        let expected_files = [CompilerDiagnosticFileHash {
            file_path: "/workspace/src/Checked.kt".to_string(),
            sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                .to_string(),
        }];
        validate_diagnostics_page(
            diagnostics_page(schema_version),
            &expected_files,
            &mut None,
            &mut None,
            &mut Vec::new(),
            &mut None,
            &mut BTreeSet::new(),
        )
    }

    #[test]
    fn diagnostics_accept_the_live_schema_version() {
        assert!(validate_diagnostics_schema(crate::SCHEMA_VERSION).is_ok());
    }

    #[test]
    fn diagnostics_reject_a_different_schema_version() {
        assert!(validate_diagnostics_schema(crate::SCHEMA_VERSION - 1).is_err());
    }

    #[test]
    fn existing_file_rollback_requires_restored_semantic_admission() {
        let refresh = serde_json::from_value(serde_json::json!({
            "refreshedFiles": ["/workspace/src/Restored.kt"],
            "removedFiles": [],
            "fullRefresh": false,
            "fileStatuses": [{
                "filePath": "/workspace/src/Restored.kt",
                "fileSystemDiscovery": "DISCOVERED",
                "sourceModuleOwnership": "OWNED",
                "indexAdmission": "ADMITTED",
                "analysisAvailability": "AVAILABLE",
                "analysisStatus": {
                    "filePath": "/workspace/src/Restored.kt",
                    "state": "ANALYZED"
                }
            }],
            "externalFailureOutcomes": [],
            "relationshipFailures": [],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 1,
            "analyzedFileCount": 1,
            "skippedFileCount": 0,
            "removedFileCount": 0,
            "attemptCount": 1,
            "elapsedMillis": 1,
            "schemaVersion": SCHEMA_VERSION
        }))
        .expect("closed refresh evidence");
        let transitions = [transition(ExactMutationPreimage::Present {
            image: AgentExactByteImage::from_bytes(b"pre"),
        })];

        assert!(validate_restored_preimage_refresh(&refresh, &transitions).is_ok());
    }

    #[test]
    fn add_file_rollback_requires_exact_removal_admission() {
        let refresh = serde_json::from_value(serde_json::json!({
            "refreshedFiles": [],
            "removedFiles": ["/workspace/src/Restored.kt"],
            "fullRefresh": false,
            "fileStatuses": [{
                "filePath": "/workspace/src/Restored.kt",
                "fileSystemDiscovery": "REMOVED",
                "sourceModuleOwnership": "NOT_APPLICABLE",
                "indexAdmission": "NOT_APPLICABLE",
                "analysisAvailability": "NOT_APPLICABLE"
            }],
            "externalFailureOutcomes": [],
            "relationshipFailures": [],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 0,
            "analyzedFileCount": 0,
            "skippedFileCount": 0,
            "removedFileCount": 1,
            "attemptCount": 1,
            "elapsedMillis": 1,
            "schemaVersion": SCHEMA_VERSION
        }))
        .expect("closed removal evidence");
        let transitions = [transition(ExactMutationPreimage::Absent)];

        assert!(validate_restored_preimage_refresh(&refresh, &transitions).is_ok());
    }

    use super::*;

    fn transition(preimage: ExactMutationPreimage) -> ExactMutationTransition {
        ExactMutationTransition {
            relative_path: "src/Restored.kt".to_string(),
            absolute_path: "/workspace/src/Restored.kt".to_string(),
            preimage,
            postimage: AgentExactByteImage::from_bytes(b"post"),
        }
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
            "schemaVersion": 6
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
            "schemaVersion": 6
        }))
        .expect("closed removal evidence");
        let transitions = [transition(ExactMutationPreimage::Absent)];

        assert!(validate_restored_preimage_refresh(&refresh, &transitions).is_ok());
    }

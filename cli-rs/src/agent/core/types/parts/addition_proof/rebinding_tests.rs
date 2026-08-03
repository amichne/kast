    use super::*;

    #[test]
    fn accepted_addition_authority_requires_zero_rebinding_candidates() {
        let baseline = AgentAdditionRebindingBaseline {
            cardinality: 1,
            dimensions: COMPLETE_ADDITION_REBINDING_DIMENSIONS.to_vec(),
            occurrences: vec![AgentAdditionRebindingOccurrence {
                range: AgentAdditionWorkspaceRange {
                    file_path: "/workspace/src/Use.kt".to_string(),
                    start_offset: 1,
                    end_offset: 2,
                },
                current_target: AgentAdditionRebindingCurrentTarget::Unresolved {
                    reason: AgentAdditionUnresolvedReason::NotFound,
                },
                provenance: AgentAdditionOccurrenceProvenance::Compiler,
            }],
        };

        assert!(baseline.validate().is_err());
    }

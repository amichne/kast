    use super::*;

    fn transition(path: &str, pre: &[u8], post: &[u8]) -> ExactMutationTransition {
        ExactMutationTransition {
            relative_path: format!("src/{path}.kt"),
            absolute_path: format!("/workspace/src/{path}.kt"),
            preimage: ExactMutationPreimage::Present {
                image: AgentExactByteImage::from_bytes(pre),
            },
            postimage: AgentExactByteImage::from_bytes(post),
        }
    }

    fn present(path: &str, bytes: &[u8]) -> RawExactFileObservation {
        RawExactFileObservation::Present {
            file_path: format!("src/{path}.kt"),
            image: AgentExactByteImage::from_bytes(bytes),
        }
    }

    #[test]
    fn recovery_classification_is_closed_over_all_pre_post_mixed_and_foreign() {
        let transitions = [
            transition("A", b"pre-a", b"post-a"),
            transition("B", b"pre-b", b"post-b"),
        ];
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"pre-a"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::AllPre
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"post-a"), present("B", b"post-b")],
            ),
            RecoveryObservationClass::AllPost
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"post-a"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::Mixed
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"foreign"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::Foreign
        );
    }

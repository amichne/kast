    use super::*;

    #[test]
    fn exact_observer_is_closed_and_binds_the_requested_relative_path() {
        let unknown = serde_json::json!({
            "type": "ABSENT",
            "filePath": "src/New.kt",
            "unexpected": true,
        });
        assert!(serde_json::from_value::<RawExactFileObservation>(unknown).is_err());

        let wrong_path = serde_json::json!({
            "type": "ABSENT",
            "filePath": "src/Other.kt",
        });
        let observation = serde_json::from_value::<RawExactFileObservation>(wrong_path)
            .expect("closed observer response");
        assert!(observation.validate_for("src/New.kt").is_err());
    }

    #[test]
    fn mutation_transition_set_requires_deterministic_unique_paths() {
        let postimage = AgentExactByteImage::from_bytes(b"a");
        let transitions = vec![
            ExactMutationTransition {
                relative_path: "src/Z.kt".to_string(),
                absolute_path: "/workspace/src/Z.kt".to_string(),
                preimage: ExactMutationPreimage::Absent,
                postimage: postimage.clone(),
            },
            ExactMutationTransition {
                relative_path: "src/A.kt".to_string(),
                absolute_path: "/workspace/src/A.kt".to_string(),
                preimage: ExactMutationPreimage::Absent,
                postimage,
            },
        ];
        assert!(validate_sorted_transition_set(Path::new("/workspace"), &transitions).is_err());
    }

    fn mutation_lease_receipt(
        plan_id: Uuid,
        workspace_root: &Path,
        ownership: WorkspaceLeaseOwnership,
        release_receipt: WorkspaceLeaseReleaseReceipt,
    ) -> MutationLeaseReceipt {
        let process = runtime::WorkspaceLeaseProcessIdentity {
            pid: 41,
            started_at: "unix:1".to_string(),
        };
        MutationLeaseReceipt {
            plan_id,
            lease_binding_sha256: MutationLeaseBindingSha256::try_from("a".repeat(64))
                .expect("lease binding digest"),
            workspace_root: workspace_root.to_path_buf(),
            workspace_kind: runtime::SemanticWorkspaceKind::StandaloneGradleWorkspace,
            backend_name: crate::cli::BackendName::Indexer,
            runtime: runtime::WorkspaceLeaseRuntimeIdentity {
                descriptor_path: "/runtime/daemons.json".to_string(),
                descriptor: runtime::ServerInstanceDescriptor {
                    workspace_root: workspace_root.display().to_string(),
                    backend_name: "indexer".to_string(),
                    backend_version: "test".to_string(),
                    runtime_instance_id: Some("runtime-1".to_string()),
                    process_start_epoch_millis: Some(1_000),
                    owner_uid: Some(501),
                    socket_file_identity: Some(runtime::RuntimeSocketFileIdentity {
                        device: 1,
                        inode: 2,
                    }),
                    transport: "uds".to_string(),
                    socket_path: "/runtime/indexer.sock".to_string(),
                    pid: process.pid,
                    schema_version: crate::SCHEMA_VERSION,
                },
                process: process.clone(),
            },
            installation: runtime::WorkspaceLeaseInstallationIdentity {
                authority: runtime::WorkspaceLeaseInstallAuthority::ActiveRelease,
                generation: "test-generation".to_string(),
                environment_sha256: "b".repeat(64),
            },
            ownership,
            owner: runtime::WorkspaceLeaseOwnerIdentity {
                process,
                session_sha256: None,
                scope: runtime::WorkspaceLeaseOwnerScope::CurrentProcess,
            },
            acquired_at: "unix:1".to_string(),
            state: WorkspaceLeaseState::Released,
            release_receipt,
            schema_version: MUTATION_LEASE_RECEIPT_SCHEMA_VERSION,
        }
    }

    #[test]
    fn mutation_lease_receipt_rejects_cross_plan_and_cross_root_substitution() {
        let plan_id = Uuid::new_v4();
        let receipt = mutation_lease_receipt(
            plan_id,
            Path::new("/workspace"),
            WorkspaceLeaseOwnership::Borrowed,
            WorkspaceLeaseReleaseReceipt::RuntimeIdlePolicy {
                released_at: "unix:2".to_string(),
            },
        );

        assert!(receipt.validate_for(plan_id, Path::new("/workspace")).is_ok());
        assert!(receipt.validate_for(Uuid::new_v4(), Path::new("/workspace")).is_err());
        assert!(receipt.validate_for(plan_id, Path::new("/other-workspace")).is_err());
    }

    #[test]
    fn mutation_lease_receipt_rejects_tampered_release_semantics() {
        let plan_id = Uuid::new_v4();
        for ownership in [
            WorkspaceLeaseOwnership::Started,
            WorkspaceLeaseOwnership::Borrowed,
        ] {
            let receipt = mutation_lease_receipt(
                plan_id,
                Path::new("/workspace"),
                ownership,
                WorkspaceLeaseReleaseReceipt::RuntimeIdlePolicy {
                    released_at: "unix:2".to_string(),
                },
            );
            assert!(receipt.validate_for(plan_id, Path::new("/workspace")).is_ok());
        }
        for ownership in [
            WorkspaceLeaseOwnership::Started,
            WorkspaceLeaseOwnership::Borrowed,
        ] {
            let receipt = mutation_lease_receipt(
                plan_id,
                Path::new("/workspace"),
                ownership,
                WorkspaceLeaseReleaseReceipt::RecoveredAbandonedOwner {
                    released_at: "unix:2".to_string(),
                },
            );
            assert!(
                receipt.validate_for(plan_id, Path::new("/workspace")).is_err(),
                "accepted recovered abandoned lease as a mutation release for {ownership:?}",
            );
        }
    }

    #[test]
    fn mutation_lease_receipt_rejects_authenticated_identity_tampering() {
        let plan_id = Uuid::new_v4();
        let receipt = mutation_lease_receipt(
            plan_id,
            Path::new("/workspace"),
            WorkspaceLeaseOwnership::Borrowed,
            WorkspaceLeaseReleaseReceipt::RuntimeIdlePolicy {
                released_at: "unix:2".to_string(),
            },
        );
        let mut wrong_runtime_root = receipt.clone();
        wrong_runtime_root.runtime.descriptor.workspace_root = "/other".to_string();
        let mut wrong_runtime_process = receipt.clone();
        wrong_runtime_process.runtime.process.pid += 1;
        let mut wrong_installation = receipt.clone();
        wrong_installation.installation.environment_sha256 = "B".repeat(64);
        let mut wrong_owner = receipt.clone();
        wrong_owner.owner.scope = runtime::WorkspaceLeaseOwnerScope::CallerSession;
        let mut wrong_state = receipt;
        wrong_state.state = WorkspaceLeaseState::Ready;

        for tampered in [
            wrong_runtime_root,
            wrong_runtime_process,
            wrong_installation,
            wrong_owner,
            wrong_state,
        ] {
            assert!(
                tampered.validate_for(plan_id, Path::new("/workspace")).is_err(),
                "accepted tampered lease identity: {tampered:?}",
            );
        }
    }

    #[test]
    fn public_mutation_lease_receipt_omits_private_authority() {
        let receipt = mutation_lease_receipt(
            Uuid::new_v4(),
            Path::new("/workspace"),
            WorkspaceLeaseOwnership::Borrowed,
            WorkspaceLeaseReleaseReceipt::RuntimeIdlePolicy {
                released_at: "unix:2".to_string(),
            },
        );
        let private = serde_json::to_value(&receipt).expect("private lease evidence");
        assert!(private.get("leaseBindingSha256").is_some());
        assert!(private.get("runtime").is_some());

        let public = serde_json::to_value(PublicMutationLeaseReceipt::from(&receipt))
            .expect("public lease receipt");
        assert_eq!(
            public,
            serde_json::json!({
                "state": "RELEASED",
                "ownership": "BORROWED",
                "releaseReceipt": {
                    "outcome": "RUNTIME_IDLE_POLICY",
                    "releasedAt": "unix:2",
                },
            }),
        );
    }

mod final_review_tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn registered_runtime_admission_revalidates_from_persisted_descriptor_directory_final_review_regression()
     {
        use std::os::unix::net::UnixListener;

        let temp = tempfile::tempdir().expect("runtime directory");
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).expect("workspace");
        let workspace = fs::canonicalize(workspace).expect("canonical workspace");
        let caller_descriptor_directory = PathBuf::from("/caller-descriptors");
        let persisted_descriptor_directory = temp.path().join("persisted-descriptors");
        fs::create_dir(&persisted_descriptor_directory).expect("persisted descriptors");
        let socket_path = temp.path().join("runtime.sock");
        let _listener = UnixListener::bind(&socket_path).expect("runtime socket");
        let candidate = runtime_candidate(&workspace, &socket_path);
        fs::write(
            persisted_descriptor_directory.join("daemons.json"),
            serde_json::to_vec(&vec![candidate.descriptor.clone()]).expect("descriptor JSON"),
        )
        .expect("descriptor registry");
        let request = semantic_runtime_request(&workspace, &caller_descriptor_directory);
        let registration = validated_registration(&persisted_descriptor_directory);

        let admission = construct_registered_admitted_runtime(
            &request,
            &registration,
            candidate,
            RuntimeAdmissionPath::Reused,
        )
        .expect("registered admission");

        assert_eq!(
            request.config.paths.descriptor_dir,
            caller_descriptor_directory
        );
        assert_eq!(
            admission.config().paths.descriptor_dir,
            persisted_descriptor_directory
        );
        let epoch = admission
            .validate_current()
            .expect("persisted descriptor revalidation");
        let _source = epoch
            .capability_ready()
            .expect("persisted source capability");
    }

    fn semantic_runtime_request(
        workspace_root: &Path,
        descriptor_directory: &Path,
    ) -> SemanticRuntimeRequest {
        let mut config = KastConfig::defaults();
        config.paths.descriptor_dir = descriptor_directory.to_path_buf();
        SemanticRuntimeRequest {
            demand: Demand::new(),
            workspace_root: workspace_root.to_path_buf(),
            config,
            workspace_kind: SemanticWorkspaceKind::StandaloneGradleWorkspace,
            availability: SemanticRuntimeAvailability::StartIfMissing,
            accept_indexing: true,
            wait_timeout_ms: 1,
            runtime_args: RuntimeArgs {
                workspace_root: Some(PathBuf::from("/workspace")),
                idea_home: None,
                wait_timeout_ms: 1,
                accept_indexing: Some(true),
                no_auto_start: None,
                socket_path: None,
                module_name: None,
                source_roots: None,
                classpath: None,
                request_timeout_ms: None,
                max_results: None,
                max_concurrent_requests: None,
                profile: false,
                profile_modes: None,
                profile_duration: None,
                profile_otlp_endpoint: None,
            },
        }
    }

    #[cfg(unix)]
    fn runtime_candidate(workspace_root: &Path, socket_path: &Path) -> RuntimeCandidateStatus {
        let pid = u64::from(std::process::id());
        let backend_version = "test".to_string();
        let descriptor = ServerInstanceDescriptor {
            workspace_root: workspace_root.display().to_string(),
            backend_name: BackendName::Indexer.canonical().to_string(),
            backend_version: backend_version.clone(),
            runtime_instance_id: Some(uuid::Uuid::nil().to_string()),
            process_start_epoch_millis: Some(
                process_start_epoch_seconds(pid).expect("process start") * 1_000,
            ),
            owner_uid: Some(u64::from(unsafe { libc::geteuid() })),
            socket_file_identity: Some(
                current_socket_file_identity(socket_path.to_str().expect("UTF-8 socket path"))
                    .expect("socket identity"),
            ),
            transport: "uds".to_string(),
            socket_path: socket_path.display().to_string(),
            pid,
            schema_version: SCHEMA_VERSION,
        };
        RuntimeCandidateStatus {
            descriptor_path: "registered-runtime".to_string(),
            descriptor,
            pid_alive: true,
            reachable: true,
            ready: true,
            runtime_status: Some(RuntimeStatusResponse {
                state: RuntimeState::Ready,
                backend_name: BackendName::Indexer.canonical().to_string(),
                backend_version: backend_version.clone(),
                workspace_root: workspace_root.display().to_string(),
                message: None,
                warnings: Vec::new(),
                source_module_names: vec!["root".to_string()],
                dependent_module_names_by_source_module_name: serde_json::Map::new(),
                reference_coverage_state: ReferenceCoverageState::Complete,
                reference_coverage_limitations: Vec::new(),
                published_workspace_generation: Some(
                    crate::published_workspace::PublishedWorkspaceGenerationManifest {
                        generation: 1,
                        identity: "workspace-state-one".to_string(),
                        source_index_generation: 1,
                        source_revision: 1,
                        reference_revision: 1,
                        graph_publication:
                            crate::published_workspace::PublishedGraphEvidence::Ready {
                                revision: 1,
                            },
                        source_index_schema_version:
                            crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION,
                        database_file: "source-index.db".to_string(),
                        published_at_epoch_millis: 1,
                        repository_overlay_file: None,
                    },
                ),
                readiness: RuntimeReadiness::ready(),
                schema_version: SCHEMA_VERSION,
            }),
            capabilities: Some(serde_json::json!({
                "backendName": BackendName::Indexer.canonical(),
                "backendVersion": backend_version,
                "workspaceRoot": workspace_root,
                "schemaVersion": SCHEMA_VERSION
            })),
            error_message: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    fn validated_registration(
        descriptor_directory: &Path,
    ) -> registration::ValidatedServiceRegistration {
        let runtime_instance_id = uuid::Uuid::nil();
        let launch = serde_json::from_value(serde_json::json!({
            "schemaVersion": 1,
            "workspaceRoot": "/workspace",
            "workspaceKey": "workspace-key",
            "runtimeInstanceId": runtime_instance_id,
            "ownerUid": 0,
            "workingDirectory": "/workspace",
            "command": ["/bin/true", "--socket-path=/runtime.sock"],
            "environment": [],
            "logFile": "/runtime.log",
            "descriptorDirectory": descriptor_directory,
            "socketPath": "/runtime.sock",
            "launcherPath": "/bin/true",
            "launcherSha256": "0".repeat(64),
            "runtimeConfigPath": "/runtime-config.json",
            "runtimeConfigSha256": "0".repeat(64)
        }))
        .expect("service launch registration");
        registration::ValidatedServiceRegistration {
            directory: PathBuf::from("/registration"),
            receipt_path: PathBuf::from("/registration/receipt.json"),
            receipt_sha256: "0".repeat(64),
            receipt: registration::ServiceRegistrationReceipt {
                schema_version: 1,
                workspace_root: "/workspace".to_string(),
                workspace_key: "workspace-key".to_string(),
                runtime_instance_id,
                launch_path: "/registration/launch.json".to_string(),
                launch_sha256: "0".repeat(64),
                definition_sha256: "0".repeat(64),
                manager: registration::ServiceManagerRegistration::Test {
                    state_path: "/manager-state.json".to_string(),
                    definition_path: "/registration/service.test.json".to_string(),
                },
            },
            launch,
        }
    }
}

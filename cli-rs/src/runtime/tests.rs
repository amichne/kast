#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn descriptor_registry_disappearance_is_an_empty_registry() {
        let descriptors = parse_descriptor_registry_read(Err(std::io::Error::from(
            std::io::ErrorKind::NotFound,
        )))
        .expect("a concurrently removed descriptor registry is empty");

        assert!(descriptors.is_empty());
    }

    #[test]
    fn descriptor_registry_preserves_non_missing_io_failures() {
        let error = parse_descriptor_registry_read(Err(std::io::Error::from(
            std::io::ErrorKind::PermissionDenied,
        )))
        .expect_err("permission failures must remain explicit");

        assert_eq!(error.code, "IO_ERROR");
    }

    fn candidate(name: &str, state: RuntimeState, indexing: bool) -> RuntimeCandidateStatus {
        RuntimeCandidateStatus {
            descriptor_path: format!("{name}:1"),
            descriptor: ServerInstanceDescriptor {
                workspace_root: "/tmp/ws".to_string(),
                backend_name: name.to_string(),
                backend_version: "test".to_string(),
                transport: "uds".to_string(),
                socket_path: "/tmp/kast.sock".to_string(),
                pid: 1,
                schema_version: SCHEMA_VERSION,
            },
            pid_alive: true,
            reachable: true,
            ready: state == RuntimeState::Ready && !indexing,
            runtime_status: Some(RuntimeStatusResponse {
                state,
                healthy: true,
                active: true,
                indexing,
                backend_name: name.to_string(),
                backend_version: "test".to_string(),
                workspace_root: "/tmp/ws".to_string(),
                message: None,
                warnings: vec![],
                source_module_names: vec![],
                dependent_module_names_by_source_module_name: Default::default(),
                reference_index_ready: false,
                schema_version: SCHEMA_VERSION,
            }),
            capabilities: None,
            error_message: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    #[test]
    fn automatic_servable_selection_rejects_backend_ambiguity() {
        let candidates = vec![
            candidate("headless", RuntimeState::Ready, false),
            candidate("idea", RuntimeState::Ready, false),
        ];

        let error = reject_ambiguous_servable_backends(
            &candidates,
            RuntimeBackendPreference::Automatic,
            false,
        )
        .expect_err("automatic selection must reject two ready backends");

        assert_eq!(error.code, "SEMANTIC_BACKEND_AMBIGUOUS");
        assert_eq!(error.details["candidateCount"], "2");
    }

    #[test]
    fn automatic_semantic_selection_uses_sole_ready_idea_over_non_macos_default() {
        let candidates = vec![SemanticBackendCandidateEvidence {
            backend_name: "idea".to_string(),
            backend_version: "test".to_string(),
            workspace_root: "/tmp/ws".to_string(),
            ready: true,
            evidence_quality: SemanticEvidenceQuality::CompilerBacked,
        }];

        let selected = automatic_semantic_backend_selection(candidates, BackendName::Headless)
            .expect("sole ready backend");

        assert_eq!(selected, BackendName::Idea);
    }

    #[test]
    fn indexing_requires_accept_indexing() {
        let candidates = vec![candidate("headless", RuntimeState::Indexing, true)];
        assert!(select_servable(&candidates, None, false).is_none());
        assert!(select_servable(&candidates, None, true).is_some());
    }

    #[test]
    fn servable_selection_respects_fixed_headless_filter() {
        let candidates = vec![
            candidate("headless", RuntimeState::Ready, false),
            candidate("idea", RuntimeState::Ready, false),
        ];
        let selected = select_servable(&candidates, Some(BackendName::Headless), false).unwrap();
        assert_eq!(selected.descriptor.backend_name, "headless");
    }

    #[test]
    fn idea_descriptor_from_another_checkout_never_matches_requested_root() {
        let requested_root = Path::new("/work/kast/.worktrees/feature");
        let descriptor = ServerInstanceDescriptor {
            workspace_root: "/work/kast".to_string(),
            backend_name: "idea".to_string(),
            backend_version: "test".to_string(),
            transport: "uds".to_string(),
            socket_path: "/tmp/kast.sock".to_string(),
            pid: 1,
            schema_version: SCHEMA_VERSION,
        };

        assert!(!descriptor_matches_workspace(&descriptor, requested_root));
    }

    #[test]
    fn runtime_status_from_another_checkout_is_rejected() {
        let candidate = candidate("idea", RuntimeState::Ready, false);
        let mut status = candidate.runtime_status.expect("runtime status");
        status.workspace_root = "/work/kast/.worktrees/other".to_string();

        let error = validate_runtime_status_identity(&candidate.descriptor, &status)
            .expect_err("other checkout status must be rejected");

        assert_eq!(error.code, "RUNTIME_IDENTITY_MISMATCH");
    }

    #[test]
    fn runtime_backend_preference_uses_the_host_default_without_cli_or_config() {
        let config = KastConfig::defaults();

        #[cfg(target_os = "macos")]
        let expected = RuntimeBackendPreference::Fixed(BackendName::Idea);
        #[cfg(not(target_os = "macos"))]
        let expected = RuntimeBackendPreference::Automatic;
        assert_eq!(
            runtime_backend_preference(&config, None),
            expected,
        );
    }

    #[test]
    fn runtime_backend_preference_preserves_cli_and_config_authority() {
        let mut config = KastConfig::defaults();
        config.runtime.default_backend = config::RuntimeDefaultBackend::Idea;

        assert_eq!(
            runtime_backend_preference(&config, None),
            RuntimeBackendPreference::Fixed(BackendName::Idea),
        );
        assert_eq!(
            runtime_backend_preference(&config, Some(BackendName::Headless)),
            RuntimeBackendPreference::Fixed(BackendName::Headless),
        );
    }

    #[test]
    fn fallback_launch_backend_uses_headless_unless_idea_is_fixed() {
        assert_eq!(
            fallback_launch_backend(RuntimeBackendPreference::Automatic),
            Some(BackendName::Headless),
        );
        assert_eq!(
            fallback_launch_backend(RuntimeBackendPreference::Fixed(BackendName::Headless)),
            Some(BackendName::Headless),
        );
        assert_eq!(
            fallback_launch_backend(RuntimeBackendPreference::Fixed(BackendName::Idea)),
            None,
        );
    }

    struct FakeIdeaLaunchOps {
        launch_result: std::cell::RefCell<Option<Result<()>>>,
        wait_result: std::cell::RefCell<Option<Result<RuntimeCandidateStatus>>>,
        launches: std::cell::RefCell<Vec<(PathBuf, PathBuf)>>,
        waits: std::cell::RefCell<Vec<u64>>,
    }

    impl FakeIdeaLaunchOps {
        fn ready() -> Self {
            Self {
                launch_result: std::cell::RefCell::new(Some(Ok(()))),
                wait_result: std::cell::RefCell::new(Some(Ok(candidate(
                    "idea",
                    RuntimeState::Ready,
                    false,
                )))),
                launches: std::cell::RefCell::new(vec![]),
                waits: std::cell::RefCell::new(vec![]),
            }
        }
    }

    impl IdeaBackendLaunchOps for FakeIdeaLaunchOps {
        fn launch(&self, command: &Path, workspace_root: &Path) -> Result<()> {
            self.launches
                .borrow_mut()
                .push((command.to_path_buf(), workspace_root.to_path_buf()));
            self.launch_result.borrow_mut().take().unwrap_or(Ok(()))
        }

        fn wait_for_servable(
            &self,
            _workspace_root: &Path,
            _accept_indexing: bool,
            wait_timeout_ms: u64,
        ) -> Result<RuntimeCandidateStatus> {
            self.waits.borrow_mut().push(wait_timeout_ms);
            self.wait_result
                .borrow_mut()
                .take()
                .unwrap_or_else(|| Ok(candidate("idea", RuntimeState::Ready, false)))
        }
    }

    #[cfg(not(target_os = "macos"))]
    #[test]
    fn idea_launch_is_skipped_unless_enabled_and_idea_is_selected() {
        let workspace = PathBuf::from("/work/kast");
        let config = KastConfig::defaults();
        let ops = FakeIdeaLaunchOps::ready();

        let selected = maybe_launch_idea_backend(
            &workspace,
            &config,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            false,
            &ops,
        )
        .unwrap();

        assert!(selected.is_none());
        assert!(ops.launches.borrow().is_empty());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn idea_launch_is_required_for_macos_idea_workspaces() {
        let workspace = PathBuf::from("/work/kast");
        let config = KastConfig::defaults();
        let ops = FakeIdeaLaunchOps::ready();

        let selected = maybe_launch_idea_backend(
            &workspace,
            &config,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            false,
            &ops,
        )
        .unwrap();

        assert!(selected.is_some());
        assert_eq!(ops.launches.borrow().len(), 1);
    }

    #[test]
    fn idea_launch_runs_configured_command_and_waits_for_descriptor() {
        let workspace = PathBuf::from("/work/kast");
        let mut config = KastConfig::defaults();
        config.runtime.default_backend = config::RuntimeDefaultBackend::Idea;
        config.runtime.idea_launch.enabled = true;
        config.runtime.idea_launch.command = PathBuf::from("/usr/local/bin/idea");
        config.runtime.idea_launch.wait_timeout_millis = std::num::NonZeroU64::new(12_345).unwrap();
        let ops = FakeIdeaLaunchOps::ready();

        let selected = maybe_launch_idea_backend(
            &workspace,
            &config,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            true,
            &ops,
        )
        .unwrap()
        .unwrap();

        assert_eq!(selected.descriptor.backend_name, "idea");
        assert_eq!(
            ops.launches.borrow().as_slice(),
            &[(PathBuf::from("/usr/local/bin/idea"), workspace)]
        );
        assert_eq!(ops.waits.borrow().as_slice(), &[12_345]);
    }

    #[test]
    fn idea_launch_surfaces_launch_failures() {
        let workspace = PathBuf::from("/work/kast");
        let mut config = KastConfig::defaults();
        config.runtime.default_backend = config::RuntimeDefaultBackend::Idea;
        config.runtime.idea_launch.enabled = true;
        let ops = FakeIdeaLaunchOps {
            launch_result: std::cell::RefCell::new(Some(Err(CliError::new(
                "IDEA_LAUNCH_FAILED",
                "boom",
            )))),
            ..FakeIdeaLaunchOps::ready()
        };

        let error = maybe_launch_idea_backend(
            &workspace,
            &config,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            false,
            &ops,
        )
        .unwrap_err();

        assert_eq!(error.code, "IDEA_LAUNCH_FAILED");
    }

    #[test]
    fn idea_launch_surfaces_wait_timeout() {
        let workspace = PathBuf::from("/work/kast");
        let mut config = KastConfig::defaults();
        config.runtime.default_backend = config::RuntimeDefaultBackend::Idea;
        config.runtime.idea_launch.enabled = true;
        let ops = FakeIdeaLaunchOps {
            wait_result: std::cell::RefCell::new(Some(Err(CliError::new(
                "RUNTIME_TIMEOUT",
                "timed out",
            )))),
            ..FakeIdeaLaunchOps::ready()
        };

        let error = maybe_launch_idea_backend(
            &workspace,
            &config,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            false,
            &ops,
        )
        .unwrap_err();

        assert_eq!(error.code, "RUNTIME_TIMEOUT");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn runtime_compatibility_separates_global_updates_from_local_capability_absence() {
        let mut facts = RuntimeCompatibilityFacts {
            plugin_version: cli::version().to_string(),
            cli_version: cli::version().to_string(),
            protocol_revision: ProtocolRevision(NonZeroU32::new(1).expect("protocol")),
            workspace_metadata_revision: WorkspaceMetadataRevision(
                NonZeroU32::new(3).expect("metadata"),
            ),
            read_capabilities: vec![
                WorkspaceReadCapability::ResolveSymbol,
                WorkspaceReadCapability::Diagnostics,
                WorkspaceReadCapability::WorkspaceFiles,
            ],
            mutation_capabilities: vec![
                WorkspaceMutationCapability::ApplyEdits,
                WorkspaceMutationCapability::RefreshWorkspace,
                WorkspaceMutationCapability::Rename,
            ],
            runtime_identity: WorkspaceRuntimeIdentity {
                implementation_version: cli::version().to_string(),
                backend_kind: WorkspaceRuntimeBackendKind::Idea,
            },
        };

        assert_eq!(
            assess_runtime_compatibility(&facts, None).expect("global assessment"),
            RuntimeCompatibilityAssessment::Compatible,
        );
        assert_eq!(
            assess_runtime_compatibility(
                &facts,
                Some(RuntimeCapability::Read(
                    WorkspaceReadCapability::CallHierarchy,
                )),
            )
            .expect("operation assessment"),
            RuntimeCompatibilityAssessment::MissingCapability {
                capability: RuntimeCapability::Read(WorkspaceReadCapability::CallHierarchy),
            },
        );

        facts
            .read_capabilities
            .retain(|capability| *capability != WorkspaceReadCapability::Diagnostics);
        assert!(matches!(
            assess_runtime_compatibility(&facts, None).expect("missing required assessment"),
            RuntimeCompatibilityAssessment::UpdateRequired {
                requirement: RuntimeCompatibilityUpdateRequirement::MissingRequiredCapability {
                    capability: RuntimeCapability::Read(WorkspaceReadCapability::Diagnostics),
                },
                ..
            }
        ));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn runtime_compatibility_can_relax_only_plugin_version_matching() {
        let mut facts = RuntimeCompatibilityFacts {
            plugin_version: "newer-plugin".to_string(),
            cli_version: cli::version().to_string(),
            protocol_revision: ProtocolRevision(NonZeroU32::new(1).expect("protocol")),
            workspace_metadata_revision: WorkspaceMetadataRevision(
                NonZeroU32::new(3).expect("metadata"),
            ),
            read_capabilities: vec![
                WorkspaceReadCapability::ResolveSymbol,
                WorkspaceReadCapability::Diagnostics,
                WorkspaceReadCapability::WorkspaceFiles,
            ],
            mutation_capabilities: vec![
                WorkspaceMutationCapability::ApplyEdits,
                WorkspaceMutationCapability::RefreshWorkspace,
                WorkspaceMutationCapability::Rename,
            ],
            runtime_identity: WorkspaceRuntimeIdentity {
                implementation_version: "newer-plugin".to_string(),
                backend_kind: WorkspaceRuntimeBackendKind::Idea,
            },
        };

        assert!(matches!(
            assess_runtime_compatibility(&facts, None).expect("strict plugin assessment"),
            RuntimeCompatibilityAssessment::UpdateRequired {
                requirement: RuntimeCompatibilityUpdateRequirement::UnsupportedReleasePair,
                ..
            }
        ));
        assert_eq!(
            assess_runtime_compatibility_with_plugin_matching(&facts, None, false)
                .expect("relaxed plugin assessment"),
            RuntimeCompatibilityAssessment::Compatible,
        );
        facts.cli_version = "newer-cli".to_string();
        assert!(matches!(
            assess_runtime_compatibility_with_plugin_matching(&facts, None, false)
                .expect("relaxed plugin assessment with mismatched CLI"),
            RuntimeCompatibilityAssessment::UpdateRequired {
                requirement: RuntimeCompatibilityUpdateRequirement::UnsupportedReleasePair,
                ..
            }
        ));
    }
}

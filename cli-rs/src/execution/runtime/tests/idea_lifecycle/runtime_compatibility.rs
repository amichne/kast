#[cfg(target_os = "macos")]
#[test]
fn runtime_compatibility_separates_global_updates_from_local_capability_absence() {
    let mut facts = RuntimeCompatibilityFacts {
        plugin_version: cli::version().to_string(),
        cli_version: cli::version().to_string(),
        protocol_revision: ProtocolRevision(NonZeroU32::new(2).expect("protocol")),
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
        protocol_revision: ProtocolRevision(NonZeroU32::new(2).expect("protocol")),
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

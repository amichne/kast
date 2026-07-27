#[test]
fn composition_distinguishes_scripts_filesystem_index_and_missing_drift() {
    let (_temp, root, fixture) = fixture();
    for (filename, create) in [
        ("Both.kt", true),
        ("IndexOnly.kt", true),
        ("Missing.kt", false),
    ] {
        fixture.insert_manifest_file(1, "src/main/kotlin/sample", filename, create);
        insert_named_metadata(&fixture, 1, filename, 1, "sample", None);
        fixture.insert_project_evidence(1, filename, ".", ":app", "main");
    }
    fixture.seed_progress("app", "COMPLETE", 3, 3);
    std::fs::write(
        root.as_path().join("src/main/kotlin/sample/BackendOnly.kt"),
        "package sample\n",
    )
    .expect("backend-only source");
    std::fs::write(root.as_path().join("build.gradle.kts"), "plugins {}\n").expect("script");
    let files = [
        "src/main/kotlin/sample/Both.kt",
        "src/main/kotlin/sample/BackendOnly.kt",
        "src/main/kotlin/sample/Missing.kt",
        "build.gradle.kts",
    ];
    let mut responses = complete_backend_responses(
        "snapshot",
        "module",
        &["src/main/kotlin/sample"],
        &[],
        &files,
    );
    responses.push(backend_result("snapshot", vec![]));
    let mut backend = ScriptedWorkspaceBackend::new(responses);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::Mixed,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");
    let drift = |path: &str| {
        snapshot
            .files()
            .iter()
            .find(|file| file.path().as_path() == Path::new(path))
            .map(|file| (file.index_state().clone(), file.drift()))
            .unwrap_or_else(|| panic!("composed file {path}"))
    };
    let both = snapshot
        .files()
        .iter()
        .find(|file| file.path().as_path() == Path::new("src/main/kotlin/sample/Both.kt"))
        .expect("composed source");

    assert_eq!(
        drift("build.gradle.kts"),
        (
            WorkspaceFileIndexState::NotApplicable,
            WorkspaceFileDrift::NotApplicable
        )
    );
    assert_eq!(
        drift("src/main/kotlin/sample/BackendOnly.kt").1,
        WorkspaceFileDrift::FilesystemOnly
    );
    assert_eq!(
        drift("src/main/kotlin/sample/IndexOnly.kt").1,
        WorkspaceFileDrift::IndexOnly
    );
    assert_eq!(
        drift("src/main/kotlin/sample/Both.kt").1,
        WorkspaceFileDrift::InSync
    );
    assert_eq!(
        drift("src/main/kotlin/sample/Missing.kt").1,
        WorkspaceFileDrift::MissingOnDisk
    );
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Complete
    );
    assert_eq!(
        snapshot.coverage().filter_evidence(),
        WorkspaceCoverageDimension::Complete
    );
    assert_eq!(both.kind(), WorkspaceFileKind::Source);
    assert_eq!(
        both.backend_modules()
            .iter()
            .map(|module| module.as_str())
            .collect::<Vec<_>>(),
        ["module"]
    );
    assert!(snapshot.limitations().is_empty());
    assert_eq!(
        backend.requests.len(),
        4,
        "the after barrier validates without repaging"
    );
}

#[test]
fn partial_possible_owner_makes_index_only_drift_unknown() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "IndexOnly.kt", true);
    insert_named_metadata(&fixture, 1, "IndexOnly.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "IndexOnly.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let mut responses = vec![
        backend_result(
            "snapshot",
            vec![
                backend_module_with_ownership(
                    "complete-owner",
                    0,
                    &[],
                    None,
                    &["src/main/kotlin/sample"],
                    &[],
                    &[],
                ),
                backend_module_with_ownership(
                    "partial-owner",
                    1,
                    &[],
                    None,
                    &[],
                    &["src/main/kotlin/sample"],
                    &[],
                ),
            ],
        ),
        Err(super::backend::BackendRpcFailure::Transport(
            "partial owner page".to_string(),
        )),
        backend_result("snapshot", vec![]),
    ];
    responses.push(backend_result("snapshot", vec![]));
    let mut backend = ScriptedWorkspaceBackend::new(responses);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::ProjectModelOwnershipUnknown),
        1
    );
}

#[test]
fn workspace_wide_stale_with_zero_modules_never_claims_index_only() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "IndexOnly.kt", true);
    insert_named_metadata(&fixture, 1, "IndexOnly.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "IndexOnly.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
    ]);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(backend.requests.len(), 4);
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.backend_coverage(),
        BackendWorkspaceCoverage::Partial
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale),
        1
    );
}

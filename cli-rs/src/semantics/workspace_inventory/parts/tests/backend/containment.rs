#[cfg(unix)]
#[test]
fn backend_paths_and_ownership_roots_require_canonical_containment() {
    use std::os::unix::fs::symlink;

    let temp = tempfile::tempdir().expect("workspace");
    let outside = tempfile::tempdir().expect("outside");
    std::fs::write(outside.path().join("Escape.kt"), "package outside\n").expect("outside file");
    symlink(outside.path(), temp.path().join("escape")).expect("escaping symlink");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");

    let mut escaping_file = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module", 1, &[], None)]),
        backend_result(
            "snapshot",
            vec![backend_module("module", 1, &["escape/Escape.kt"], None)],
        ),
        backend_result("snapshot", vec![]),
    ]);
    let file_inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut escaping_file,
    );

    let escape_root = temp.path().join("escape").display().to_string();
    let mut escaping_root = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                1,
                &[],
                None,
                &[escape_root.as_str()],
                &[],
                &[],
            )],
        ),
        backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                1,
                &["Inside.kt"],
                None,
                &[escape_root.as_str()],
                &[],
                &[],
            )],
        ),
        backend_result("snapshot", vec![]),
    ]);
    let root_inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut escaping_root,
    );

    assert!(file_inventory.files().is_empty());
    assert_eq!(file_inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(
        file_inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::PathContainmentUnprovable),
        Some(&1)
    );
    assert_eq!(root_inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(
        escaping_root.requests.len(),
        3,
        "valid module files are still paged"
    );
    assert_eq!(root_inventory.files().len(), 1);
    assert_eq!(
        root_inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::PathContainmentUnprovable),
        Some(&1)
    );
}

#[test]
fn module_fingerprint_retains_sorted_content_roots_and_dependencies() {
    let temp = tempfile::tempdir().expect("workspace");
    std::fs::create_dir_all(temp.path().join("src")).expect("source root");
    std::fs::create_dir_all(temp.path().join("content-a")).expect("content a");
    std::fs::create_dir_all(temp.path().join("content-b")).expect("content b");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let collect = |content_root: &str| {
        let mut backend = ScriptedWorkspaceBackend::new(vec![
            backend_result(
                "same-token",
                vec![backend_module_with_ownership(
                    "module",
                    0,
                    &[],
                    None,
                    &["src"],
                    &[content_root],
                    &["dependency"],
                )],
            ),
            backend_result("same-token", vec![]),
        ]);
        super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        )
    };

    let first = collect("content-a");
    let second = collect("content-b");

    assert_ne!(first.stamp(), second.stamp());
    let module = first.modules().values().next().expect("module");
    assert_eq!(
        module
            .content_roots()
            .iter()
            .map(|root| root.as_path())
            .collect::<Vec<_>>(),
        [Path::new("content-a")]
    );
    assert_eq!(
        module
            .dependency_module_names()
            .iter()
            .map(|name| name.as_str())
            .collect::<Vec<_>>(),
        ["dependency"]
    );
}

#[test]
fn backend_composition_stamp_ignores_opaque_lease_tokens_but_tracks_candidates() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let collect = |token: &str, file: &str| {
        let mut backend = ScriptedWorkspaceBackend::new(vec![
            backend_result(token, vec![backend_module("module", 1, &[], None)]),
            backend_result(token, vec![backend_module("module", 1, &[file], None)]),
            backend_result(token, vec![]),
        ]);
        super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        )
    };

    let first = collect("lease-a", "src/A.kt");
    let same_evidence_new_lease = collect("lease-b", "src/A.kt");
    let changed_candidate = collect("lease-c", "src/B.kt");

    assert_eq!(first.stamp(), same_evidence_new_lease.stamp());
    assert_ne!(first.stamp(), changed_candidate.stamp());
}

#[test]
fn workspace_root_is_a_valid_typed_module_content_root() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let workspace_root = root.as_path().display().to_string();
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                0,
                &[],
                None,
                &[],
                &[&workspace_root],
                &[],
            )],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Complete);
    let module = inventory.modules().values().next().expect("module");
    assert_eq!(
        module
            .content_roots()
            .iter()
            .map(|content_root| content_root.as_path())
            .collect::<Vec<_>>(),
        [Path::new("")]
    );
}

#[test]
fn unsorted_or_duplicate_module_fingerprint_metadata_fails_closed() {
    for source_roots in [vec!["src-b", "src-a"], vec!["src-a", "src-a"]] {
        let temp = tempfile::tempdir().expect("workspace");
        std::fs::create_dir_all(temp.path().join("src-a")).expect("src a");
        std::fs::create_dir_all(temp.path().join("src-b")).expect("src b");
        let root = WorkspaceRoot::try_from(temp.path()).expect("root");
        let mut backend = ScriptedWorkspaceBackend::new(vec![backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                0,
                &[],
                None,
                &source_roots,
                &[],
                &[],
            )],
        )]);

        let inventory = super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        );

        assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Unavailable);
        assert!(inventory.files().is_empty());
    }
}

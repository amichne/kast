#[test]
fn backend_pages_are_exhausted_in_opaque_cursor_order_and_shared_paths_keep_every_owner() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let snapshot = "snapshot-alpha";
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            snapshot,
            vec![
                backend_module("module-b", 2, &[], None),
                backend_module("module-a", 3, &[], None),
            ],
        ),
        backend_result(
            snapshot,
            vec![backend_module(
                "module-a",
                3,
                &["src/Shared.kt"],
                Some("opaque not an offset"),
            )],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-a", 3, &["src/A.kt"], Some("a-last"))],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-a", 3, &["src/Z.kt"], None)],
        ),
        backend_result(
            snapshot,
            vec![backend_module(
                "module-b",
                2,
                &["src/Shared.kt"],
                Some("b-last"),
            )],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-b", 2, &["src/B.kt"], None)],
        ),
        backend_result(snapshot, vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut backend,
    );

    let shared = inventory
        .files()
        .iter()
        .find(|(path, _)| path.as_path() == Path::new("src/Shared.kt"))
        .expect("shared path");
    let owners: Vec<_> = shared.1.iter().map(|owner| owner.as_str()).collect();
    let cursors: Vec<_> = backend
        .requests
        .iter()
        .filter_map(|request| request["params"]["pageToken"].as_str())
        .collect();
    let module_a = inventory
        .modules()
        .values()
        .find(|module| module.name().as_str() == "module-a")
        .expect("module-a inventory");

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Complete);
    assert_eq!(inventory.files().len(), 4);
    assert_eq!(owners, ["module-a", "module-b"]);
    assert_eq!(cursors, ["opaque not an offset", "a-last", "b-last"]);
    assert_eq!(module_a.declared_file_count(), 3);
    assert!(
        backend.requests[1]["params"].get("pageToken").is_none(),
        "the first exact-module request is cursorless: {:?}",
        backend.requests[1]
    );
    assert!(
        inventory
            .modules()
            .values()
            .all(|module| module.coverage() == BackendModuleCoverage::Complete)
    );
}

#[test]
fn repeated_page_handle_makes_only_its_module_partial() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module-a", 2, &[], None)]),
        backend_result(
            "snapshot",
            vec![backend_module("module-a", 2, &["src/A.kt"], Some("same"))],
        ),
        backend_result(
            "snapshot",
            vec![backend_module("module-a", 2, &["src/B.kt"], Some("same"))],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(inventory.files().is_empty());
    assert_eq!(
        inventory
            .modules()
            .values()
            .next()
            .map(|module| module.coverage()),
        Some(BackendModuleCoverage::Partial)
    );
    assert_eq!(
        inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::BackendPageIncomplete),
        Some(&1)
    );
}

fn backend_api_failure(
    code: &str,
    reason: Option<&str>,
) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
    Err(super::backend::BackendRpcFailure::Api {
        code: code.to_string(),
        message: code.to_string(),
        reason: reason.map(str::to_string),
    })
}

#[test]
fn overlapping_or_short_module_pages_are_partial_and_never_publish_their_candidates() {
    for responses in [
        vec![
            backend_result("snapshot", vec![backend_module("module", 2, &[], None)]),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], Some("last"))],
            ),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], None)],
            ),
            backend_result("snapshot", vec![]),
        ],
        vec![
            backend_result("snapshot", vec![backend_module("module", 2, &[], None)]),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], None)],
            ),
            backend_result("snapshot", vec![]),
        ],
    ] {
        let temp = tempfile::tempdir().expect("workspace");
        let root = WorkspaceRoot::try_from(temp.path()).expect("root");
        let mut backend = ScriptedWorkspaceBackend::new(responses);

        let inventory = super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        );

        assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
        assert!(inventory.files().is_empty());
    }
}

#[test]
fn empty_nonterminal_module_page_fails_closed_without_following_unbounded_tokens() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module", 2, &[], None)]),
        backend_result(
            "snapshot",
            vec![backend_module("module", 2, &[], Some("unique-empty-page"))],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(
        backend.requests.len(),
        3,
        "the next opaque token is not followed"
    );
    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(inventory.files().is_empty());
    assert_eq!(
        inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::BackendPageIncomplete),
        Some(&1)
    );
}

#[test]
fn generic_page_failure_is_local_to_the_requested_module() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![
                backend_module("module-a", 1, &[], None),
                backend_module("module-b", 1, &[], None),
            ],
        ),
        Err(super::backend::BackendRpcFailure::Transport(
            "module-a unavailable".to_string(),
        )),
        backend_result(
            "snapshot",
            vec![backend_module("module-b", 1, &["src/B.kt"], None)],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(inventory.files().len(), 1);
    assert_eq!(
        inventory
            .modules()
            .iter()
            .map(|(name, module)| (name.as_str(), module.coverage()))
            .collect::<Vec<_>>(),
        [
            ("module-a", BackendModuleCoverage::Partial),
            ("module-b", BackendModuleCoverage::Complete)
        ]
    );
}

#[test]
fn second_stale_attempt_is_bounded_and_discards_all_stale_candidates() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "old-snapshot",
            vec![backend_module("old-module", 1, &[], None)],
        ),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_result(
            "new-snapshot",
            vec![backend_module("new-module", 1, &[], None)],
        ),
        backend_result(
            "new-snapshot",
            vec![backend_module("new-module", 1, &["src/New.kt"], None)],
        ),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(backend.requests.len(), 5, "no third metadata request");
    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(inventory.files().is_empty());
    assert_eq!(
        inventory
            .modules()
            .keys()
            .map(|name| name.as_str())
            .collect::<Vec<_>>(),
        ["new-module"]
    );
    assert_eq!(
        inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale),
        Some(&1)
    );
}

#[test]
fn project_model_metadata_failure_is_unavailable_and_page_failure_is_workspace_partial() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut metadata_failure = ScriptedWorkspaceBackend::new(vec![backend_api_failure(
        "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
        Some("PROJECT_MODEL_UNAVAILABLE"),
    )]);
    let unavailable = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut metadata_failure,
    );

    let mut page_failure = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module", 1, &[], None)]),
        backend_api_failure(
            "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
            Some("RUNTIME_INDEXING"),
        ),
    ]);
    let partial = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut page_failure,
    );

    assert_eq!(
        unavailable.coverage(),
        BackendWorkspaceCoverage::Unavailable
    );
    assert_eq!(partial.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(partial.files().is_empty());
    assert_eq!(
        unavailable
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::ProjectModelUnavailable),
        Some(&1)
    );
    assert_eq!(
        partial
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::RuntimeIndexing),
        Some(&1)
    );
}

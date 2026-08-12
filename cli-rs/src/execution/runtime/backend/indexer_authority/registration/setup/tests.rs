use super::*;

#[test]
fn missing_service_root_keeps_typed_identity_deleted_workspace_registration_review_regression() {
    let parent = tempfile::tempdir().expect("workspace parent");
    let missing = parent.path().join("missing");
    let candidate = registered_setup_root(missing.to_str().expect("workspace path"))
        .expect("missing registered root candidate");
    let replacement = parent.path().join("replacement");
    fs::create_dir(&replacement).expect("replacement root");
    std::os::unix::fs::symlink(&replacement, &missing).expect("replacement symlink");

    assert!(
        matches!(candidate, WorkspaceRootCandidate::MissingNormalized(root) if root == missing)
    );
}

#[test]
fn missing_descriptor_root_stays_blocked_deleted_workspace_registration_review_regression() {
    let root = tempfile::tempdir()
        .expect("workspace parent")
        .path()
        .join("missing");

    let error = descriptor_setup_root(root.to_str().expect("workspace path"), &BTreeMap::new())
        .expect_err("descriptor-only missing root must block setup");

    assert_eq!(error.code, "SETUP_RUNTIME_PREFLIGHT_BLOCKED");
}

#[test]
fn publication_temporary_requires_v4_uuid_review_regression() {
    assert!(canonical_uuid("11111111-1111-4111-8111-111111111111").is_some());
    assert!(canonical_uuid("11111111-1111-1111-8111-111111111111").is_none());
}

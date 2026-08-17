use super::*;
use crate::manifest;

#[test]
fn runtime_registry_noise_is_removed_before_service_inspection() {
    let fixture = tempfile::tempdir().expect("runtime registry fixture");
    let runtime = fixture.path().join("runtime");
    let services = runtime.join("services");
    fs::create_dir_all(&services).expect("runtime services directory");

    fs::write(services.join(".DS_Store"), "finder metadata").expect("top-level Finder metadata");
    let unrelated_target = fixture.path().join("unrelated");
    fs::create_dir(&unrelated_target).expect("unrelated directory");
    std::os::unix::fs::symlink(&unrelated_target, services.join("misplaced-link"))
        .expect("runtime registry symlink noise");
    let invalid_workspace = services.join("not-a-workspace-key");
    fs::create_dir(&invalid_workspace).expect("invalid workspace directory");
    fs::write(invalid_workspace.join("note"), "noise").expect("invalid workspace contents");

    let valid_workspace = services.join("a".repeat(64));
    fs::create_dir(&valid_workspace).expect("valid workspace directory");
    fs::write(valid_workspace.join(".DS_Store"), "finder metadata")
        .expect("nested Finder metadata");
    let invalid_registration = valid_workspace.join("not-a-runtime-instance");
    fs::create_dir(&invalid_registration).expect("invalid registration directory");
    fs::write(invalid_registration.join("note"), "noise").expect("invalid registration contents");

    let mut paths = manifest::default_resolved_paths();
    paths.runtime_dir = runtime;
    let roots = registered_service_roots(&paths).expect("normalize owned runtime registry");

    assert!(roots.is_empty(), "noise must not manufacture runtime roots");
    assert!(
        unrelated_target.is_dir(),
        "registry cleanup must not follow symlink targets"
    );
    assert!(
        fs::read_dir(&services)
            .expect("normalized services directory")
            .all(|entry| entry.expect("service entry").path() == valid_workspace),
        "only the valid workspace directory may remain"
    );
    assert_eq!(
        fs::read_dir(&valid_workspace)
            .expect("normalized workspace directory")
            .count(),
        0,
        "invalid registration entries must be removed"
    );
}

#[test]
fn runtime_registry_noise_cleanup_keeps_valid_registration_shapes_strict() {
    let fixture = tempfile::tempdir().expect("runtime registry fixture");
    let runtime = fixture.path().join("runtime");
    let registration = runtime
        .join("services")
        .join("a".repeat(64))
        .join("11111111-1111-4111-8111-111111111111");
    fs::create_dir_all(&registration).expect("valid-shaped registration directory");

    let mut paths = manifest::default_resolved_paths();
    paths.runtime_dir = runtime;
    registered_service_roots(&paths).expect_err("incomplete valid-shaped registration must fail");

    assert!(
        registration.is_dir(),
        "valid-shaped registration evidence must not be deleted before validation"
    );
}

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

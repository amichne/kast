use super::*;
use std::path::Path;

#[test]
fn symlinked_stop_uses_canonical_lifecycle_lock_remaining_review_regression() {
    let temp = tempfile::tempdir().expect("workspace fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let workspace_alias = temp.path().join("workspace-alias");
    std::os::unix::fs::symlink(&workspace, &workspace_alias).expect("workspace alias");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace_alias.to_str().expect("workspace alias path"),
        ])
        .output()
        .expect("runtime stop through workspace alias");

    assert_success(&stop, "runtime stop through workspace alias");
    let result = output_json(&stop);
    assert_eq!(result["workspaceRoot"], workspace.display().to_string());
    let lock_directory = default_install_root(&home)
        .join("state/runtime")
        .join("workspace-launch-locks");
    let canonical_lock = lock_directory.join(format!("{}.lock", workspace_hash(&workspace)));
    let alias_lock = lock_directory.join(format!("{}.lock", workspace_hash(&workspace_alias)));

    assert!(
        canonical_lock.exists(),
        "canonical lifecycle lock is absent"
    );
    assert!(!alias_lock.exists(), "alias-specific lifecycle lock exists");
}

fn workspace_hash(path: &Path) -> String {
    hex::encode(Sha256::digest(path.to_string_lossy().as_bytes()))[..12].to_string()
}

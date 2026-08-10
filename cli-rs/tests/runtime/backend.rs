#![cfg(not(target_os = "macos"))]

#[path = "../support/mod.rs"]
mod support;

use support::*;

fn standalone_workspace(root: &Path) {
    std::fs::create_dir_all(root).expect("workspace");
    std::fs::write(
        root.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("settings");
}

#[test]
fn semantic_demand_without_an_installed_indexer_reports_the_supported_distribution() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    standalone_workspace(&workspace);

    let demand = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "agent",
            "symbol",
            "--query",
            "Foo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("semantic demand");

    assert!(
        !demand.status.success(),
        "semantic demand should require an installed indexer"
    );
    let stderr = String::from_utf8_lossy(&demand.stderr);
    assert!(stderr.contains("- Code: NO_INDEXER_AVAILABLE"), "{stderr}");
    assert!(
        stderr.contains("supportedDistribution") && stderr.contains("linux-indexer-tarball"),
        "stderr should identify the supported private indexer distribution: {stderr}",
    );
}

#[test]
fn agent_verify_reports_the_single_indexer_identity_without_starting_it() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    standalone_workspace(&workspace);

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("agent verify");

    assert!(
        !verify.status.success(),
        "verify should not start the indexer"
    );
    let output: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("agent verify JSON");
    assert_eq!(output["error"]["code"], "NO_INDEXER_AVAILABLE");
    assert_eq!(
        output["error"]["details"]["semanticWorkspace"]["backendName"],
        "indexer",
    );
}

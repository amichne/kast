mod support;

use serde_json::Value;
use support::metrics::seed_source_index;
use support::*;

#[test]
fn top_level_help_exposes_repo_native_demo() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("top-level help");

    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    assert!(
        stdout
            .lines()
            .any(|line| line.trim_start().starts_with("demo")),
        "top-level help should expose the repo-native demo: {stdout}"
    );
}

#[test]
fn captured_demo_returns_ranked_repo_native_story_snapshot() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("captured demo");

    assert!(
        demo.status.success(),
        "captured demo should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&demo.stdout),
        String::from_utf8_lossy(&demo.stderr)
    );
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo json");
    assert_eq!(response["type"], "KAST_DEMO");
    assert_eq!(response["availability"], "indexOnly");
    assert_eq!(response["workspaceRoot"], workspace.display().to_string());
    assert_eq!(response["mutates"], false);
    let candidates = response["candidates"].as_array().expect("candidates");
    assert!(
        candidates.iter().any(|candidate| {
            candidate["kind"] == "impactHub" && candidate["fqName"] == "lib.Foo"
        }),
        "ranked candidates should include the highest-impact symbol: {response:#}"
    );
    assert!(
        response["chapters"]
            .as_array()
            .expect("chapters")
            .iter()
            .any(|chapter| chapter["chapter"] == "impact" && chapter["available"] == true),
        "index-only stories should retain impact evidence: {response:#}"
    );
    assert!(
        response["help"]
            .as_array()
            .expect("help")
            .iter()
            .any(|entry| entry
                .as_str()
                .is_some_and(|entry| { entry.contains("kast agent impact --symbol lib.Foo") })),
        "snapshot should expose an exact reusable public command: {response:#}"
    );
}

#[test]
fn unavailable_demo_reports_the_platform_setup_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("unavailable demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(response["code"], "DEMO_SOURCE_INDEX_MISSING");
    let message = response["message"].as_str().expect("message");
    if cfg!(target_os = "macos") {
        assert!(
            message.contains("open this repository in IntelliJ IDEA or Android Studio"),
            "macOS remediation should name the plugin-owned setup path: {response:#}"
        );
    } else {
        assert!(
            message.contains("kast setup --workspace-root"),
            "headless remediation should name the CLI-owned setup path: {response:#}"
        );
    }
}

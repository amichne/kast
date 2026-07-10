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
    assert_eq!(
        candidates
            .iter()
            .map(|candidate| candidate["kind"].as_str().expect("candidate kind"))
            .collect::<Vec<_>>(),
        vec!["impactHub", "callChainHub", "semanticAmbiguity"],
        "the fixture supports all three deterministic story kinds: {response:#}"
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

#[test]
fn requested_demo_symbol_fails_loudly_when_the_index_has_no_match() {
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
            "--symbol",
            "NoSuchSymbol",
        ])
        .output()
        .expect("requested symbol demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(response["code"], "DEMO_SYMBOL_NOT_FOUND");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("NoSuchSymbol")),
        "the error should preserve the user's missing symbol query: {response:#}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn interactive_demo_renders_in_a_real_pty_without_changing_sources() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);
    let source = workspace.join("app/A.kt");
    let before = std::fs::read(&source).expect("source before demo");

    let mut child = Command::new("script")
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .env("KAST_TEST_BIN", env!("CARGO_BIN_EXE_kast"))
        .env("KAST_TEST_WORKSPACE", &workspace)
        .env("TERM", "xterm-256color")
        .args([
            "-q",
            "/dev/null",
            "/bin/sh",
            "-c",
            "stty rows 30 cols 120; exec \"$KAST_TEST_BIN\" --output human demo --workspace-root \"$KAST_TEST_WORKSPACE\"",
        ])
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("spawn interactive demo");
    child
        .stdin
        .take()
        .expect("script stdin")
        .write_all(b"q")
        .expect("quit demo");
    let output = child.wait_with_output().expect("wait for demo");

    assert!(
        output.status.success(),
        "interactive demo should exit cleanly: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("Kast Semantic Story"),
        "the real PTY should render the guided experience: {}",
        String::from_utf8_lossy(&output.stdout)
    );
    assert_eq!(
        std::fs::read(&source).expect("source after demo"),
        before,
        "the interactive demo must not mutate user code"
    );
}

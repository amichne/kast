#[path = "../support/mod.rs"]
mod support;

use serde_json::Value;
use support::metrics::*;
use support::*;

#[test]
fn reads_metrics_directly_from_source_index_db() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);
    write_macos_plugin_workspace_metadata(&workspace);

    let fan_in = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "inspect",
            "metrics",
            "fan-in",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--limit",
            "1",
        ])
        .output()
        .expect("metrics fan-in");
    assert!(
        fan_in.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fan_in.stderr)
    );
    let fan_in_stdout = String::from_utf8_lossy(&fan_in.stdout);
    assert!(fan_in_stdout.starts_with("Kast metrics fan-in\n==================="));
    assert!(!fan_in_stdout.contains("# Kast metrics fan-in"));
    assert!(fan_in_stdout.contains("targetFqName=lib.Foo"));
    assert!(fan_in_stdout.contains("occurrenceCount=3"));
    assert!(serde_json::from_slice::<Value>(&fan_in.stdout).is_err());

    let fan_in_json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "metrics",
            "fan-in",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--limit",
            "1",
        ])
        .output()
        .expect("metrics fan-in json");
    assert!(
        fan_in_json.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fan_in_json.stderr)
    );
    let fan_in_json_stdout = String::from_utf8_lossy(&fan_in_json.stdout);
    assert!(fan_in_json_stdout.contains("\"targetFqName\": \"lib.Foo\""));
    assert!(fan_in_json_stdout.contains("\"occurrenceCount\": 3"));

    let search = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "metrics",
            "search",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "Foo",
        ])
        .output()
        .expect("metrics search");
    assert!(
        search.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&search.stderr)
    );
    assert!(String::from_utf8_lossy(&search.stdout).contains("\"lib.Foo\""));

    let short_search = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "metrics",
            "search",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "Fo",
        ])
        .output()
        .expect("metrics short search");
    assert!(
        short_search.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&short_search.stderr)
    );
    assert!(String::from_utf8_lossy(&short_search.stdout).contains("\"lib.FooWidget\""));

    let metrics_help = kast(&home, &config_home)
        .args(["developer", "inspect", "metrics", "--help"])
        .output()
        .expect("metrics help");
    assert!(
        metrics_help.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&metrics_help.stderr)
    );
}

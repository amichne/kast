mod support;

use support::*;

fn decode_toon(bytes: &[u8]) -> serde_json::Value {
    let output = std::str::from_utf8(bytes).expect("toon output should be utf-8");
    toon_format::decode_default(output.trim()).expect("toon output should decode")
}

#[test]
fn agent_tools_can_emit_toon_equivalent_to_default_json() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let json = kast(&home, &config_home)
        .args(["agent", "tools"])
        .output()
        .expect("agent tools json");
    assert!(
        json.status.success(),
        "agent tools json should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    let json_value: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("agent tools json");

    let toon = kast(&home, &config_home)
        .args(["agent", "--format", "toon", "tools"])
        .output()
        .expect("agent tools toon");
    assert!(
        toon.status.success(),
        "agent tools toon should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&toon.stdout),
        String::from_utf8_lossy(&toon.stderr)
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&toon.stdout).is_err(),
        "toon output should not be parseable as JSON"
    );
    let toon_value = decode_toon(&toon.stdout);

    assert_eq!(toon_value, json_value);
    assert!(
        toon.stdout.len() < json.stdout.len(),
        "toon agent tools output should be smaller than pretty JSON: json={}, toon={}",
        json.stdout.len(),
        toon.stdout.len()
    );
}

#[test]
fn agent_call_validation_errors_can_emit_toon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let call = kast(&home, &config_home)
        .args(["agent", "--format", "toon", "call", "symbol/resolve"])
        .output()
        .expect("agent call toon validation");
    assert!(
        !call.status.success(),
        "invalid agent call should fail validation"
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&call.stdout).is_err(),
        "toon validation output should not be parseable as JSON"
    );
    let output = decode_toon(&call.stdout);

    assert_eq!(output["ok"], false, "{output:#}");
    assert_eq!(output["method"], "symbol/resolve", "{output:#}");
    assert_eq!(
        output["error"]["code"], "AGENT_REQUEST_INVALID",
        "{output:#}"
    );
}

#[test]
fn agent_workflow_toon_stdout_keeps_step_files_json() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let out_dir = temp.path().join("workflow");
    std::fs::create_dir_all(&home).expect("home");

    let workflow = kast(&home, &config_home)
        .args([
            "agent",
            "--format",
            "toon",
            "workflow",
            "symbol",
            "--dry-run",
            "--out-dir",
            out_dir.to_str().expect("workflow path"),
            "--symbol",
            "Kast",
            "--references",
        ])
        .output()
        .expect("agent workflow toon dry-run");
    assert!(
        workflow.status.success(),
        "workflow dry-run should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&workflow.stdout).is_err(),
        "toon workflow stdout should not be parseable as JSON"
    );
    let output = decode_toon(&workflow.stdout);

    assert_eq!(output["ok"], true, "{output:#}");
    assert_eq!(output["method"], "agent/workflow/symbol", "{output:#}");
    assert_eq!(output["result"]["dryRun"], true, "{output:#}");

    for path in [
        out_dir.join("workflow.json"),
        out_dir.join("symbol-query/input.json"),
        out_dir.join("symbol-query/stdout.json"),
        out_dir.join("symbol-resolve/input.json"),
        out_dir.join("symbol-references/input.json"),
    ] {
        let content = std::fs::read_to_string(&path)
            .unwrap_or_else(|error| panic!("read {}: {error}", path.display()));
        serde_json::from_str::<serde_json::Value>(&content)
            .unwrap_or_else(|error| panic!("{} should remain JSON: {error}", path.display()));
    }
}

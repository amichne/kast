mod support;

use support::*;

fn decode_toon(bytes: &[u8]) -> serde_json::Value {
    let output = std::str::from_utf8(bytes).expect("toon output should be utf-8");
    toon_format::decode_default(output.trim()).expect("toon output should decode")
}

#[test]
fn agent_rename_plan_default_toon_matches_explicit_json() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
        ])
        .output()
        .expect("agent rename json");
    assert!(
        json.status.success(),
        "agent rename json should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    let json_value: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("agent rename json");

    let toon = kast(&home, &config_home)
        .args([
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
        ])
        .output()
        .expect("agent rename toon");
    assert!(
        toon.status.success(),
        "agent rename toon should succeed: stdout={}, stderr={}",
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
        "toon agent rename output should be smaller than pretty JSON: json={}, toon={}",
        json.stdout.len(),
        toon.stdout.len()
    );
}

#[test]
fn agent_call_removed_errors_can_emit_toon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let call = kast(&home, &config_home)
        .args(["agent", "call", "symbol/resolve"])
        .output()
        .expect("agent call toon removal");
    assert!(!call.status.success(), "removed agent call should fail");
    assert!(
        serde_json::from_slice::<serde_json::Value>(&call.stdout).is_err(),
        "toon validation output should not be parseable as JSON"
    );
    let output = decode_toon(&call.stdout);

    assert_eq!(output["ok"], false, "{output:#}");
    assert_eq!(output["method"], "agent/call", "{output:#}");
    assert_eq!(
        output["error"]["code"], "AGENT_COMMAND_REMOVED",
        "{output:#}"
    );
}

#[test]
fn agent_rename_plan_is_read_only_until_apply() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
        ])
        .output()
        .expect("agent rename plan");
    assert!(
        plan.status.success(),
        "rename plan should succeed without backend dispatch: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    assert_eq!(output["ok"], true, "{output:#}");
    assert_eq!(output["method"], "agent/rename", "{output:#}");
    assert_eq!(
        output["result"]["type"], "KAST_AGENT_RENAME_PLAN",
        "{output:#}"
    );
    assert_eq!(output["result"]["applyRequired"], true, "{output:#}");
    assert_eq!(
        output["result"]["request"]["method"], "symbol/rename",
        "{output:#}"
    );
    assert!(
        !output["result"]["request"].to_string().contains("offset"),
        "{output:#}"
    );
}

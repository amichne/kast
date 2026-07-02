mod support;

use support::*;

#[test]
fn packaged_agent_call_requires_agent_tools_preflight() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        r#"#!/bin/sh
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  call\n  tools\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "tools" ] && [ "$5" = "--full" ]; then
  printf '{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","schemaVersion":3,"catalogSha256":"0000000000000000000000000000000000000000000000000000000000000000","toolCount":0,"invocation":{"argv":["/wrong/kast","agent","call","<method>"]},"tools":[]}}\n'
  exit 0
fi
printf 'unexpected fake kast args:' >&2
printf ' %s' "$@" >&2
printf '\n' >&2
exit 64
"#,
    )
    .expect("fake kast");
    set_executable_for_test(&fake_bin);

    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/kast-agent-call.py");
    let call = Command::new("python3")
        .arg(&script)
        .arg("symbol/query")
        .arg("--params-json")
        .arg(r#"{"query":"Widget"}"#)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .output()
        .expect("run packaged call helper");
    assert!(
        !call.status.success(),
        "invalid agent tools envelope should fail before dispatch: stdout={}, stderr={}",
        String::from_utf8_lossy(&call.stdout),
        String::from_utf8_lossy(&call.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&call.stdout).expect("call helper json");
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue["code"] == "KAST_AGENT_TOOLS_UNAVAILABLE"),
        "{stdout:#}"
    );
    assert_eq!(
        stdout["process"]["preflight"], "agent tools --full",
        "{stdout:#}"
    );
    assert!(
        !String::from_utf8_lossy(&call.stderr).contains("unexpected fake kast args"),
        "helper should not dispatch after invalid agent tools preflight"
    );
}

#[test]
fn packaged_agent_call_rejects_agent_tools_metadata_mismatch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        r#"#!/bin/sh
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  call\n  tools\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "tools" ] && [ "$5" = "--full" ]; then
  printf '{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","schemaVersion":3,"catalogSha256":"0000000000000000000000000000000000000000000000000000000000000000","toolCount":1,"invocation":{"argv":["%s","agent","call","<method>"]},"tools":[]}}\n' "$0"
  exit 0
fi
printf 'unexpected fake kast args:' >&2
printf ' %s' "$@" >&2
printf '\n' >&2
exit 64
"#,
    )
    .expect("fake kast");
    set_executable_for_test(&fake_bin);

    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/kast-agent-call.py");
    let call = Command::new("python3")
        .arg(&script)
        .arg("symbol/query")
        .arg("--params-json")
        .arg(r#"{"query":"Widget"}"#)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .output()
        .expect("run packaged call helper");
    assert!(
        !call.status.success(),
        "metadata mismatch should fail before dispatch: stdout={}, stderr={}",
        String::from_utf8_lossy(&call.stdout),
        String::from_utf8_lossy(&call.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&call.stdout).expect("call helper json");
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue["code"] == "KAST_AGENT_TOOLS_UNAVAILABLE"),
        "{stdout:#}"
    );
    assert_eq!(
        stdout["process"]["preflight"], "agent tools --full",
        "{stdout:#}"
    );
    assert!(
        !String::from_utf8_lossy(&call.stderr).contains("unexpected fake kast args"),
        "helper should not dispatch after invalid agent tools metadata"
    );
}

#[test]
fn packaged_agent_call_uses_selected_binary_in_backend_recovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        r#"#!/bin/sh
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  call\n  tools\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "tools" ] && [ "$5" = "--full" ]; then
  printf '{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","schemaVersion":3,"catalogSha256":"0000000000000000000000000000000000000000000000000000000000000000","toolCount":0,"invocation":{"argv":["%s","agent","call","<method>"]},"tools":[]}}\n' "$0"
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "call" ]; then
  printf '{"ok":false,"method":"symbol/query","error":{"code":"NO_BACKEND_AVAILABLE","message":"backend missing"}}\n'
  exit 1
fi
printf 'unexpected fake kast args:' >&2
printf ' %s' "$@" >&2
printf '\n' >&2
exit 64
"#,
    )
    .expect("fake kast");
    set_executable_for_test(&fake_bin);
    let expected_binary = fake_bin
        .canonicalize()
        .expect("canonical fake bin")
        .display()
        .to_string();
    let expected_recovery = format!(
        "{expected_binary} runtime up --workspace-root {} --backend idea",
        workspace
            .canonicalize()
            .expect("canonical workspace")
            .display()
    );

    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/kast-agent-call.py");
    let call = Command::new("python3")
        .arg(&script)
        .arg("symbol/query")
        .arg("--params-json")
        .arg(r#"{"query":"Widget"}"#)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .output()
        .expect("run packaged call helper");
    assert!(
        !call.status.success(),
        "backend failure should be reported with recovery: stdout={}, stderr={}",
        String::from_utf8_lossy(&call.stdout),
        String::from_utf8_lossy(&call.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&call.stdout).expect("call helper json");
    assert!(
        stdout["recovery"]
            .as_array()
            .expect("recovery")
            .iter()
            .any(|command| command == &expected_recovery),
        "{stdout:#}"
    );
}

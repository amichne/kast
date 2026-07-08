mod support;

use support::*;

fn fake_minimal_kast_script(agent_tools_response: &str) -> String {
    format!(
        r#"#!/bin/sh
if [ "$1" = "--help" ]; then
  printf 'Usage: kast\nCommands:\n  ready\n  repair\n  setup\n  agent\n  developer\n'
  exit 0
fi
if [ "$1" = "ready" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast ready\n'
  exit 0
fi
if [ "$1" = "repair" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast repair\n'
  exit 0
fi
if [ "$1" = "setup" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast setup\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  verify\n  symbol\n  impact\n  diagnostics\n  rename\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$3" = "--help" ]; then
  printf 'Usage: kast agent %s\n' "$2"
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "tools" ]; then
  printf '{}\n'
  exit 1
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "call" ]; then
  printf '{{"ok":false,"method":"agent/call","error":{{"code":"AGENT_COMMAND_REMOVED","message":"removed"}},"schemaVersion":3}}\n'
  exit 1
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "agent" ] && [ "$4" = "workflow" ]; then
  printf '{{"ok":false,"method":"agent/workflow","error":{{"code":"AGENT_COMMAND_REMOVED","message":"removed"}},"schemaVersion":3}}\n'
  exit 1
fi
if [ "$1" = "version" ]; then
  printf 'Kast CLI 0.1.0\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "ready" ]; then
  printf '{{"ok":true,"issues":[],"warnings":[]}}\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "developer" ] && [ "$4" = "inspect" ] && [ "$5" = "paths" ]; then
  printf '{{"root":"%s","warnings":[]}}\n' "$PWD"
  exit 0
fi
printf 'unexpected fake kast args:' >&2
printf ' %s' "$@" >&2
printf '\n' >&2
exit 64
"#,
        agent_tools_response
    )
}

fn removed_agent_tools_response() -> &'static str {
    r#"{"ok":false,"method":"agent/tools","error":{"code":"AGENT_COMMAND_REMOVED","message":"removed"},"schemaVersion":3}"#
}

#[cfg(not(target_os = "macos"))]
#[test]
fn packaged_verifier_prefers_manifest_resource_checksums() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let skill_root = workspace.join(".agents/skills");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let init = Command::new("git")
        .arg("-C")
        .arg(&workspace)
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );

    let repair = kast(&home, &config_home)
        .current_dir(&workspace)
        .args(["repair", "--apply"])
        .output()
        .expect("repair");
    assert!(
        repair.status.success(),
        "repair --apply should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "setup",
            "--skill-target-dir",
            skill_root.to_str().expect("skill target"),
            "--force",
        ])
        .output()
        .expect("install skill guidance");
    assert!(
        install.status.success(),
        "setup should install skill guidance: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );

    let fake_skill_root = temp.path().join("fake-skill-root");
    std::fs::create_dir_all(&fake_skill_root).expect("fake skill root");
    std::fs::write(fake_skill_root.join("SKILL.md"), "# Out-of-date skill\n")
        .expect("fake stale skill");

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--skill-root")
        .arg(&fake_skill_root)
        .arg("--kast-bin")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run verifier");
    assert!(
        verify.status.success(),
        "manifest-backed skill should verify despite stale source root: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let verify_json: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("verifier json");
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsRemoved"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentCallRemoved"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentWorkflowRemoved"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentVerifyAvailable"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentSymbolAvailable"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentRenameAvailable"], true,
        "{verify_json:#}"
    );
    assert!(
        verify_json["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .all(|warning| warning["code"] != "SKILLS_STALE"),
        "{verify_json:#}"
    );
    let skill_target = verify_json["checks"]["skills"]["targets"]
        .as_array()
        .expect("skill targets")
        .iter()
        .find(|target| {
            target["path"]
                .as_str()
                .expect("target path")
                .ends_with(".agents/skills/kast")
        })
        .expect("manifest-backed skill target");
    assert!(
        skill_target["manifestResource"].is_object(),
        "{skill_target:#}"
    );
    assert_eq!(
        skill_target["manifestOutputMismatches"]
            .as_array()
            .expect("manifest output mismatches")
            .len(),
        0
    );

    std::fs::write(
        workspace.join(".agents/skills/kast/SKILL.md"),
        "tampered installed skill\n",
    )
    .expect("tamper installed skill");
    let tampered = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--skill-root")
        .arg(&fake_skill_root)
        .arg("--kast-bin")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--require-skill")
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run tampered verifier");
    assert!(
        !tampered.status.success(),
        "tampered manifest-backed skill should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&tampered.stdout),
        String::from_utf8_lossy(&tampered.stderr)
    );
    let tampered_json: serde_json::Value =
        serde_json::from_slice(&tampered.stdout).expect("tampered verifier json");
    let tampered_target = tampered_json["checks"]["skills"]["targets"]
        .as_array()
        .expect("skill targets")
        .iter()
        .find(|target| {
            target["path"]
                .as_str()
                .expect("target path")
                .ends_with(".agents/skills/kast")
        })
        .expect("tampered skill target");
    let expected_tampered_target_dir = Path::new(
        tampered_target["path"]
            .as_str()
            .expect("tampered target path"),
    )
    .parent()
    .expect("tampered target parent");
    let expected_tampered_recovery = format!(
        "{} setup --skill-target-dir {} --force",
        env!("CARGO_BIN_EXE_kast"),
        expected_tampered_target_dir.display()
    );
    let tampered_issue = tampered_json["issues"]
        .as_array()
        .expect("issues")
        .iter()
        .find(|issue| issue["code"] == "SKILLS_STALE")
        .unwrap_or_else(|| panic!("missing SKILLS_STALE issue: {tampered_json:#}"));
    assert_eq!(
        tampered_issue["recovery"], expected_tampered_recovery,
        "{tampered_json:#}"
    );
}

#[test]
fn packaged_verifier_rejects_still_public_agent_tools() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        fake_minimal_kast_script(
            r#"{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","tools":[]},"schemaVersion":3}"#,
        ),
    )
    .expect("fake kast");
    set_executable_for_test(&fake_bin);

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .output()
        .expect("run verifier");
    assert!(
        !verify.status.success(),
        "verifier should reject still-public agent tools: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verifier json");
    assert_eq!(
        stdout["checks"]["commandSurface"]["agentToolsRemoved"], false,
        "{stdout:#}"
    );
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue["code"] == "KAST_AGENT_TOOLS_STILL_PUBLIC"),
        "{stdout:#}"
    );
}

#[test]
fn packaged_verifier_uses_selected_binary_in_skill_recovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        fake_minimal_kast_script(removed_agent_tools_response()),
    )
    .expect("fake kast");
    set_executable_for_test(&fake_bin);
    let expected_binary = fake_bin
        .canonicalize()
        .expect("canonical fake bin")
        .display()
        .to_string();

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .arg("--require-skill")
        .output()
        .expect("run verifier");
    assert!(
        !verify.status.success(),
        "verifier should report missing required skill: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verifier json");
    assert_eq!(
        stdout["checks"]["commandSurface"]["agentToolsRemoved"], true,
        "{stdout:#}"
    );
    let expected_skill_target_dir = Path::new(
        stdout["checks"]["skills"]["targets"][0]["path"]
            .as_str()
            .expect("default skill target"),
    )
    .parent()
    .expect("default skill target parent");
    let expected_skill_recovery = format!(
        "{} setup --skill-target-dir {} --force",
        expected_binary,
        expected_skill_target_dir.display()
    );
    let skill_issue = stdout["issues"]
        .as_array()
        .expect("issues")
        .iter()
        .find(|issue| issue["code"] == "SKILLS_STALE")
        .unwrap_or_else(|| panic!("missing SKILLS_STALE issue: {stdout:#}"));
    assert_eq!(
        skill_issue["recovery"], expected_skill_recovery,
        "{stdout:#}"
    );
    assert!(
        stdout["recovery"]
            .as_array()
            .expect("recovery")
            .iter()
            .any(|command| command == &expected_skill_recovery),
        "{stdout:#}"
    );
}

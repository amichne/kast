mod support;

use support::*;

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
        .args(["ready", "--fix"])
        .output()
        .expect("ready repair");
    assert!(
        repair.status.success(),
        "ready --fix should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_root.to_str().expect("skill target"),
            "--force",
        ])
        .output()
        .expect("install skill");
    assert!(
        install.status.success(),
        "skill install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );

    let fake_skill_root = temp.path().join("fake-skill-root");
    std::fs::create_dir_all(fake_skill_root.join("references")).expect("fake references");
    std::fs::copy(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/kast-skill/references/commands.json"),
        fake_skill_root.join("references/commands.json"),
    )
    .expect("fake commands catalog");
    std::fs::write(
        fake_skill_root.join("references/workflows.md"),
        "# Out-of-date workflow guidance\n",
    )
    .expect("fake stale workflow reference");

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
        verify_json["checks"]["commandSurface"]["agentToolsEnvelopeOk"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsType"], "KAST_AGENT_TOOLS",
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsMetadataValid"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsSchemaVersion"], 3,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgvOk"], true,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgv"][0],
        env!("CARGO_BIN_EXE_kast"),
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgvExpected"][0],
        env!("CARGO_BIN_EXE_kast"),
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgv"][1], "agent",
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgv"][2], "call",
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsInvocationArgv"][3], "<method>",
        "{verify_json:#}"
    );
    assert!(
        verify_json["checks"]["commandSurface"]["agentToolsToolCount"]
            .as_u64()
            .expect("agent tools count")
            >= 13,
        "{verify_json:#}"
    );
    assert_eq!(
        verify_json["checks"]["commandSurface"]["agentToolsDeclaredToolCount"],
        verify_json["checks"]["commandSurface"]["agentToolsToolCount"],
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
        skill_target["contentMismatches"]
            .as_array()
            .expect("content mismatches")
            .len(),
        0
    );
    assert_eq!(
        skill_target["manifestOutputMismatches"]
            .as_array()
            .expect("manifest output mismatches")
            .len(),
        0
    );

    let missing_target = temp.path().join("missing-resource/kast");
    let missing_output = missing_target.join("SKILL.md");
    let manifest_path = install_manifest_path(&home);
    let mut install_manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).expect("install manifest"))
            .expect("install manifest json");
    install_manifest["repos"]
        .as_array_mut()
        .expect("manifest repos")
        .push(serde_json::json!({
            "path": temp.path().join("missing-resource").display().to_string(),
            "resources": [{
                "kind": "SKILL",
                "targetPath": missing_target.display().to_string(),
                "primitiveVersion": "0.1.0",
                "sourceBundleSha256": "0".repeat(64),
                "outputPaths": [missing_output.display().to_string()],
                "outputChecksums": [{
                    "path": missing_output.display().to_string(),
                    "sha256": "0".repeat(64)
                }],
                "installedAt": "2026-01-01T00:00:00Z"
            }]
        }));
    std::fs::write(
        &manifest_path,
        serde_json::to_vec_pretty(&install_manifest).expect("serialize manifest"),
    )
    .expect("write install manifest");
    let unrelated_stale = Command::new("python3")
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
        .expect("run verifier with unrelated stale resource");
    assert!(
        unrelated_stale.status.success(),
        "explicit skill verification should tolerate unrelated ready resource output issues: stdout={}, stderr={}",
        String::from_utf8_lossy(&unrelated_stale.stdout),
        String::from_utf8_lossy(&unrelated_stale.stderr)
    );
    let unrelated_stale_json: serde_json::Value =
        serde_json::from_slice(&unrelated_stale.stdout).expect("unrelated stale verifier json");
    assert_eq!(
        unrelated_stale_json["checks"]["ready"]["resourceOutputIssuesTolerated"], true,
        "{unrelated_stale_json:#}"
    );
    assert!(
        unrelated_stale_json["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning["code"] == "KAST_READY_RESOURCE_OUTPUTS"),
        "{unrelated_stale_json:#}"
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
    assert!(
        tampered_json["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue["code"] == "SKILLS_STALE"),
        "{tampered_json:#}"
    );
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
        "{} agent setup skill --target-dir {} --source-dir {} --force",
        env!("CARGO_BIN_EXE_kast"),
        expected_tampered_target_dir.display(),
        fake_skill_root
            .canonicalize()
            .expect("canonical fake skill root")
            .display()
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
    assert!(
        !tampered_target["manifestOutputMismatches"]
            .as_array()
            .expect("manifest output mismatches")
            .is_empty(),
        "{tampered_target:#}"
    );
}

#[test]
fn packaged_verifier_rejects_agent_tools_invocation_for_another_binary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        r#"#!/bin/sh
if [ "$1" = "--help" ]; then
  printf 'Usage: kast\nCommands:\n  ready\n  agent\n  runtime\n  inspect\n'
  exit 0
fi
if [ "$1" = "ready" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast ready\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  setup\n  workflow\n  tools\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "setup" ] && [ "$3" = "--help" ]; then
  printf 'Usage: kast agent setup\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "workflow" ] && [ "$3" = "--help" ]; then
  printf 'Usage: kast agent workflow\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "tools" ]; then
  printf '{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","schemaVersion":3,"catalogSha256":"0000000000000000000000000000000000000000000000000000000000000000","toolCount":0,"invocation":{"argv":["/wrong/kast","agent","call","<method>"]},"tools":[]}}\n'
  exit 0
fi
if [ "$1" = "version" ]; then
  printf 'Kast CLI 0.1.0\n'
  exit 0
fi
if [ "$1" = "install" ] && [ "$2" = "--help" ]; then
  printf 'error: unrecognized subcommand install\n' >&2
  exit 1
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "ready" ]; then
  printf '{"ok":true,"issues":[],"warnings":[]}\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "inspect" ] && [ "$4" = "paths" ]; then
  printf '{"root":"%s","warnings":[]}\n' "$PWD"
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
        "verifier should reject agent tools argv for a different binary: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verifier json");
    assert_eq!(
        stdout["checks"]["commandSurface"]["agentToolsInvocationArgvOk"], false,
        "{stdout:#}"
    );
    assert_eq!(
        stdout["checks"]["commandSurface"]["agentToolsInvocationArgvExpected"][0],
        fake_bin
            .canonicalize()
            .expect("canonical fake bin")
            .display()
            .to_string(),
        "{stdout:#}"
    );
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue["code"] == "KAST_AGENT_TOOLS_UNAVAILABLE"),
        "{stdout:#}"
    );
    assert!(
        !String::from_utf8_lossy(&verify.stderr).contains("unexpected fake kast args"),
        "verifier should not dispatch unexpected fake commands"
    );
}

#[test]
fn packaged_verifier_uses_selected_binary_in_resource_recovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        &fake_bin,
        r#"#!/bin/sh
if [ "$1" = "--help" ]; then
  printf 'Usage: kast\nCommands:\n  ready\n  agent\n  runtime\n  inspect\n'
  exit 0
fi
if [ "$1" = "ready" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast ready\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "--help" ]; then
  printf 'Usage: kast agent\nCommands:\n  setup\n  workflow\n  tools\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "setup" ] && [ "$3" = "--help" ]; then
  printf 'Usage: kast agent setup\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "workflow" ] && [ "$3" = "--help" ]; then
  printf 'Usage: kast agent workflow\n'
  exit 0
fi
if [ "$1" = "agent" ] && [ "$2" = "tools" ]; then
  printf '{"ok":true,"method":"agent/tools","result":{"type":"KAST_AGENT_TOOLS","schemaVersion":3,"catalogSha256":"0000000000000000000000000000000000000000000000000000000000000000","toolCount":0,"invocation":{"argv":["%s","agent","call","<method>"]},"tools":[]}}\n' "$0"
  exit 0
fi
if [ "$1" = "version" ]; then
  printf 'Kast CLI 0.1.0\n'
  exit 0
fi
if [ "$1" = "install" ] && [ "$2" = "--help" ]; then
  printf 'error: unrecognized subcommand install\n' >&2
  exit 1
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "ready" ]; then
  printf '{"ok":true,"issues":[],"warnings":[]}\n'
  exit 0
fi
if [ "$1" = "--output" ] && [ "$2" = "json" ] && [ "$3" = "inspect" ] && [ "$4" = "paths" ]; then
  printf '{"root":"%s","warnings":[]}\n' "$PWD"
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
    let expected_skill_target_dir = Path::new(
        stdout["checks"]["skills"]["targets"][0]["path"]
            .as_str()
            .expect("default skill target"),
    )
    .parent()
    .expect("default skill target parent");
    let expected_skill_recovery = format!(
        "{} agent setup skill --target-dir {} --force",
        expected_binary,
        expected_skill_target_dir.display()
    );
    assert_eq!(
        stdout["checks"]["commandSurface"]["agentToolsInvocationArgvOk"], true,
        "{stdout:#}"
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

    let verify_instructions = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .arg("--require-instructions")
        .output()
        .expect("run verifier for instructions");
    assert!(
        !verify_instructions.status.success(),
        "verifier should report missing required instructions: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify_instructions.stdout),
        String::from_utf8_lossy(&verify_instructions.stderr)
    );
    let instructions_stdout: serde_json::Value =
        serde_json::from_slice(&verify_instructions.stdout).expect("verifier json");
    let expected_instruction_target_dir = Path::new(
        instructions_stdout["checks"]["instructions"]["targets"][0]["path"]
            .as_str()
            .expect("default instruction target"),
    )
    .parent()
    .expect("default instruction target parent");
    let expected_instruction_recovery = format!(
        "{} agent setup instructions --target-dir {} --force",
        expected_binary,
        expected_instruction_target_dir.display()
    );
    let instruction_issue = instructions_stdout["issues"]
        .as_array()
        .expect("issues")
        .iter()
        .find(|issue| issue["code"] == "INSTRUCTIONS_STALE")
        .unwrap_or_else(|| panic!("missing INSTRUCTIONS_STALE issue: {instructions_stdout:#}"));
    assert_eq!(
        instruction_issue["recovery"], expected_instruction_recovery,
        "{instructions_stdout:#}"
    );
    assert!(
        instructions_stdout["recovery"]
            .as_array()
            .expect("recovery")
            .iter()
            .any(|command| command == &expected_instruction_recovery),
        "{instructions_stdout:#}"
    );

    let explicit_skill_target = workspace.join("host-agent/skills");
    let verify_explicit_skill = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .arg("--require-skill")
        .arg("--skill-target-dir")
        .arg(&explicit_skill_target)
        .output()
        .expect("run verifier for explicit skill target");
    assert!(
        !verify_explicit_skill.status.success(),
        "verifier should report missing explicit skill target: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify_explicit_skill.stdout),
        String::from_utf8_lossy(&verify_explicit_skill.stderr)
    );
    let explicit_skill_stdout: serde_json::Value =
        serde_json::from_slice(&verify_explicit_skill.stdout).expect("verifier json");
    let expected_explicit_skill_target_dir = Path::new(
        explicit_skill_stdout["checks"]["skills"]["targets"]
            .as_array()
            .expect("skill targets")
            .iter()
            .find(|target| {
                target["path"]
                    .as_str()
                    .expect("target path")
                    .ends_with("host-agent/skills/kast")
            })
            .expect("explicit skill target")["path"]
            .as_str()
            .expect("explicit skill target path"),
    )
    .parent()
    .expect("explicit skill target parent");
    let expected_explicit_skill_recovery = format!(
        "{} agent setup skill --target-dir {} --force",
        expected_binary,
        expected_explicit_skill_target_dir.display()
    );
    let explicit_skill_issue = explicit_skill_stdout["issues"]
        .as_array()
        .expect("issues")
        .iter()
        .find(|issue| issue["code"] == "SKILLS_STALE")
        .unwrap_or_else(|| panic!("missing SKILLS_STALE issue: {explicit_skill_stdout:#}"));
    assert_eq!(
        explicit_skill_issue["recovery"], expected_explicit_skill_recovery,
        "{explicit_skill_stdout:#}"
    );
}

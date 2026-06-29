mod support;

use support::*;

#[test]
fn agent_package_verify_workflow_dry_run_writes_native_command_argv() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let out_dir = temp.path().join("package-verify-workflow");
    let copilot_target_dir = workspace.join("host-agent/github");
    let skill_target_dir = workspace.join("host-agent/skills");
    let instructions_target_dir = workspace.join("host-agent/instructions");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--dry-run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--out-dir",
            out_dir.to_str().expect("workflow out dir"),
            "--require-copilot",
            "--copilot-target-dir",
            copilot_target_dir.to_str().expect("copilot target"),
            "--require-skill",
            "--skill-target-dir",
            skill_target_dir.to_str().expect("skill target"),
            "--require-instructions",
            "--instructions-target-dir",
            instructions_target_dir
                .to_str()
                .expect("instructions target"),
        ])
        .output()
        .expect("agent workflow package-verify dry-run");

    assert!(
        workflow.status.success(),
        "package verify dry-run should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow envelope json");
    let summary = &stdout["result"]["steps"][0]["summary"];
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(summary["ok"], true, "{stdout}");
    assert_eq!(summary["dryRun"], true, "{stdout}");
    assert_eq!(summary["method"], "package/verify", "{stdout}");
    assert!(
        summary.get("nextRequest").is_none(),
        "native package verification must not advertise a backend JSON-RPC request: {stdout}"
    );
    let next_argv = summary["nextCommandArgv"]
        .as_array()
        .expect("next command argv");
    assert_eq!(next_argv[0], env!("CARGO_BIN_EXE_kast"), "{stdout}");
    assert_eq!(next_argv[1], "--output", "{stdout}");
    assert_eq!(next_argv[2], "json", "{stdout}");
    assert_eq!(next_argv[3], "agent", "{stdout}");
    assert_eq!(next_argv[4], "workflow", "{stdout}");
    assert_eq!(next_argv[5], "package-verify", "{stdout}");
    assert!(
        next_argv.iter().any(|arg| arg == "--require-copilot"),
        "{stdout}"
    );
    assert!(
        next_argv.iter().any(|arg| arg == "--require-skill"),
        "{stdout}"
    );
    assert!(
        next_argv.iter().any(|arg| arg == "--require-instructions"),
        "{stdout}"
    );
    assert!(
        next_argv.iter().any(|arg| arg
            .as_str()
            .is_some_and(|value| value.ends_with("host-agent/github"))),
        "{stdout}"
    );
    assert!(
        next_argv.iter().any(|arg| arg
            .as_str()
            .is_some_and(|value| value.ends_with("host-agent/skills"))),
        "{stdout}"
    );
    assert!(
        next_argv.iter().any(|arg| arg
            .as_str()
            .is_some_and(|value| value.ends_with("host-agent/instructions"))),
        "{stdout}"
    );
    let input: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(out_dir.join("ready/input.json")).unwrap())
            .expect("package verify input json");
    assert_eq!(input["requireCopilot"], true, "{input}");
    assert_eq!(input["requireSkill"], true, "{input}");
    assert_eq!(input["requireInstructions"], true, "{input}");
    assert!(
        input["copilotTargetDir"]
            .as_str()
            .expect("copilot target")
            .ends_with("host-agent/github"),
        "{input}"
    );
}

#[test]
fn agent_package_verify_workflow_accepts_required_explicit_resource_targets() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let copilot_target_dir = workspace.join("host-agent/github");
    let skill_target_dir = workspace.join("host-agent/skills");
    let instructions_target_dir = workspace.join("host-agent/instructions");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
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
    let skill = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_target_dir.to_str().expect("skill target"),
            "--force",
        ])
        .output()
        .expect("install explicit skill");
    assert!(
        skill.status.success(),
        "explicit skill target should install: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr)
    );
    let instructions = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "instructions",
            "--target-dir",
            instructions_target_dir
                .to_str()
                .expect("instructions target"),
            "--force",
        ])
        .output()
        .expect("install explicit instructions");
    assert!(
        instructions.status.success(),
        "explicit instructions target should install: stdout={}, stderr={}",
        String::from_utf8_lossy(&instructions.stdout),
        String::from_utf8_lossy(&instructions.stderr)
    );
    let copilot = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            copilot_target_dir.to_str().expect("copilot target"),
            "--force",
        ])
        .output()
        .expect("install explicit copilot");
    assert!(
        copilot.status.success(),
        "explicit copilot target should install: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr)
    );

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--require-copilot",
            "--require-skill",
            "--require-instructions",
            "--copilot-target-dir",
            copilot_target_dir.to_str().expect("copilot target"),
            "--skill-target-dir",
            skill_target_dir.to_str().expect("skill target"),
            "--instructions-target-dir",
            instructions_target_dir
                .to_str()
                .expect("instructions target"),
        ])
        .output()
        .expect("agent workflow package-verify");

    assert!(
        workflow.status.success(),
        "package verify workflow should accept explicit resources: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow envelope json");
    let summary = &stdout["result"]["steps"][0]["summary"];
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(summary["ok"], true, "{stdout}");
    assert_eq!(summary["requiredResources"]["ok"], true, "{stdout}");
    let copilot_targets = summary["requiredResources"]["copilotPackage"]["targets"]
        .as_array()
        .expect("copilot targets");
    assert_eq!(copilot_targets.len(), 1, "{stdout}");
    assert_eq!(copilot_targets[0]["current"], true, "{stdout}");
    assert!(
        copilot_targets[0]["targetPath"]
            .as_str()
            .expect("copilot target path")
            .ends_with("host-agent/github"),
        "{stdout}"
    );
    assert!(
        copilot_targets[0]["manifestResource"].is_object(),
        "{stdout}"
    );
    let skill_targets = summary["requiredResources"]["skills"]["targets"]
        .as_array()
        .expect("skill targets");
    assert_eq!(skill_targets.len(), 1, "{stdout}");
    assert_eq!(skill_targets[0]["current"], true, "{stdout}");
    assert!(
        skill_targets[0]["targetPath"]
            .as_str()
            .expect("skill target path")
            .ends_with("host-agent/skills/kast"),
        "{stdout}"
    );
    assert!(skill_targets[0]["manifestResource"].is_object(), "{stdout}");
    let instruction_targets = summary["requiredResources"]["instructions"]["targets"]
        .as_array()
        .expect("instruction targets");
    assert_eq!(instruction_targets.len(), 1, "{stdout}");
    assert_eq!(instruction_targets[0]["current"], true, "{stdout}");
    assert!(
        instruction_targets[0]["targetPath"]
            .as_str()
            .expect("instruction target path")
            .ends_with("host-agent/instructions/kast"),
        "{stdout}"
    );
    assert!(
        instruction_targets[0]["manifestResource"].is_object(),
        "{stdout}"
    );
}

#[test]
fn agent_package_verify_workflow_rejects_missing_required_explicit_skill_target() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let skill_target_dir = workspace.join("host-agent/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
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

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--require-skill",
            "--skill-target-dir",
            skill_target_dir.to_str().expect("skill target"),
        ])
        .output()
        .expect("agent workflow package-verify");

    assert!(
        !workflow.status.success(),
        "package verify workflow should reject the missing explicit skill target: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow envelope json");
    let summary = &stdout["result"]["steps"][0]["summary"];
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(summary["ok"], false, "{stdout}");
    assert_eq!(summary["requiredResources"]["ok"], false, "{stdout}");
    let skill_targets = summary["requiredResources"]["skills"]["targets"]
        .as_array()
        .expect("skill targets");
    assert_eq!(skill_targets.len(), 1, "{stdout}");
    assert_eq!(skill_targets[0]["current"], false, "{stdout}");
    assert!(skill_targets[0]["manifestResource"].is_null(), "{stdout}");
    assert!(
        skill_targets[0]["targetPath"]
            .as_str()
            .expect("skill target path")
            .ends_with("host-agent/skills/kast"),
        "{stdout}"
    );
    let required_issue = summary["requiredResources"]["issues"]
        .as_array()
        .expect("required resource issues")
        .iter()
        .find(|issue| {
            issue["code"].as_str().expect("issue code")
                == "AGENT_WORKFLOW_REQUIRED_SKILL_MISSING_OR_STALE"
        })
        .unwrap_or_else(|| panic!("missing required skill issue: {stdout}"));
    let recovery_argv = required_issue["recoveryArgv"]
        .as_array()
        .expect("recovery argv");
    assert_eq!(recovery_argv[0], env!("CARGO_BIN_EXE_kast"), "{stdout}");
    assert_eq!(recovery_argv[1], "agent", "{stdout}");
    assert_eq!(recovery_argv[2], "setup", "{stdout}");
    assert_eq!(recovery_argv[3], "skill", "{stdout}");
    assert_eq!(recovery_argv[4], "--target-dir", "{stdout}");
    assert!(
        recovery_argv[5]
            .as_str()
            .expect("recovery target")
            .ends_with("host-agent/skills"),
        "{stdout}"
    );
    assert_eq!(recovery_argv[6], "--force", "{stdout}");
    assert!(
        summary["issues"]
            .as_array()
            .expect("summary issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .expect("issue")
                .contains("AGENT_WORKFLOW_REQUIRED_SKILL_MISSING_OR_STALE")),
        "{stdout}"
    );
}

#[test]
fn agent_write_validate_workflow_requires_mutation_opt_in() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let workflow = kast(&home, &config_home)
        .args([
            "agent",
            "workflow",
            "write-validate",
            "--mode",
            "create",
            "--file-path",
            temp.path()
                .join("Example.kt")
                .to_str()
                .expect("example path"),
            "--content",
            "class Example",
        ])
        .output()
        .expect("agent workflow write-validate");

    assert!(
        !workflow.status.success(),
        "write workflow without opt-in should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow error json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(
        stdout["error"]["code"], "AGENT_WORKFLOW_MUTATION_REQUIRES_OPT_IN",
        "{stdout}"
    );
}

#[test]
fn ready_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let install_dir = install_root.join("current/lib/backends/headless/headless-0.0.1");
    let runtime_libs = install_dir.join("runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["backend:headless"],
            "managedPaths": ["current/lib/backends/headless"],
            "backends": [{
                "name": "headless",
                "version": "0.0.1",
                "installDir": install_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");
    let stdout = String::from_utf8_lossy(&ready.stdout);

    assert!(
        !ready.status.success(),
        "ready should fail for stale backend"
    );
    assert!(stdout.contains("\"ok\": false"), "{stdout}");
    assert!(stdout.contains("\"minimumBackendVersion\""), "{stdout}");
    assert!(stdout.contains("0.0.1"), "{stdout}");
    assert!(stdout.contains("older than required"), "{stdout}");
}

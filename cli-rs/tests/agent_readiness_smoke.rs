mod support;

use support::*;

#[test]
fn missing_expected_skill_reports_install_repair_instead_of_quarantine() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");

    let ready = kast(&home, &config_home)
        .env_remove("CODEX_HOME")
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "ready",
            "--for",
            "agent",
            "--workspace-root",
        ])
        .arg(&workspace)
        .output()
        .expect("agent ready");

    assert!(
        !ready.status.success(),
        "a missing effective skill must block readiness"
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    let missing_skill = payload["agentEnvironment"]["skills"]["candidates"]
        .as_array()
        .expect("skill candidates")
        .iter()
        .find(|candidate| candidate["state"] == "missing")
        .unwrap_or_else(|| panic!("missing expected skill candidate: {payload:#}"));
    let repair = missing_skill["repairCommand"]
        .as_str()
        .unwrap_or_else(|| panic!("missing skill repair command: {missing_skill:#}"));
    assert!(
        !repair.starts_with("mv "),
        "a missing path cannot be quarantined: {repair}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn agent_ready_uses_only_the_machine_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let codex_home = temp.path().join("codex-home");
    let workspace = temp.path().join("workspace");
    let nested_workspace = workspace.join("module");
    std::fs::create_dir_all(&nested_workspace).expect("nested workspace");

    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    write_macos_plugin_workspace_metadata_for_cli(
        &workspace,
        &homebrew_binary,
        env!("CARGO_PKG_VERSION"),
    );
    let stale_skill = codex_home.join("skills/kast/SKILL.md");
    std::fs::create_dir_all(stale_skill.parent().expect("stale skill parent"))
        .expect("stale skill parent");
    std::fs::write(
        &stale_skill,
        "---\nname: kast\ndescription: stale fixture\n---\nUse removed commands.\n",
    )
    .expect("stale Codex skill");
    let workspace_skill = nested_workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(workspace_skill.parent().expect("workspace skill parent"))
        .expect("workspace skill parent");
    std::fs::write(&workspace_skill, "workspace-owned").expect("workspace skill");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .env("CODEX_HOME", &codex_home)
        .current_dir(&nested_workspace)
        .args([
            "--output",
            "json",
            "ready",
            "--for",
            "agent",
            "--workspace-root",
        ])
        .arg(&workspace)
        .output()
        .expect("agent ready");

    assert!(
        ready.status.success(),
        "machine-scoped resources must ignore provider and worktree copies: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    let candidates = payload["agentEnvironment"]["skills"]["candidates"]
        .as_array()
        .expect("skill candidates");
    assert_eq!(candidates.len(), 1, "{payload:#}");
    assert_eq!(
        candidates[0]["path"],
        home.join(".agents/skills/kast/SKILL.md")
            .display()
            .to_string(),
    );
    assert_eq!(candidates[0]["source"], "machine");
    assert_eq!(candidates[0]["state"], "managed");
    assert_eq!(candidates[0]["compatible"], true);
    assert_eq!(
        payload["agentEnvironment"]["guidance"]["source"],
        "machine-skill",
    );
}

#[cfg(target_os = "macos")]
#[test]
fn workspace_resources_do_not_affect_machine_readiness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    write_macos_plugin_workspace_metadata_for_cli(
        &workspace,
        &homebrew_binary,
        env!("CARGO_PKG_VERSION"),
    );
    let guidance = workspace.join("AGENTS.local.md");
    let skill = workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(skill.parent().expect("skill parent")).expect("skill parent");
    std::fs::write(&guidance, "user guidance").expect("guidance");
    std::fs::write(&skill, "user skill").expect("skill");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .env_remove("CODEX_HOME")
        .args([
            "--output",
            "json",
            "ready",
            "--for",
            "agent",
            "--workspace-root",
        ])
        .arg(&workspace)
        .output()
        .expect("agent ready");

    assert!(
        ready.status.success(),
        "worktree resources are outside machine authority: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&guidance).expect("guidance after readiness"),
        "user guidance",
    );
    assert_eq!(
        std::fs::read_to_string(&skill).expect("skill after readiness"),
        "user skill",
    );
}

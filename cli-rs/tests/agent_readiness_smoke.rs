mod support;

use support::*;

#[cfg(not(target_os = "macos"))]
#[test]
fn readiness_delegates_guidance_and_skill_authority_to_the_codex_plugin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let ready = kast(&home, &config_home)
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

    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert!(payload["agentEnvironment"].get("skills").is_none());
    assert!(payload["agentEnvironment"].get("guidance").is_none());
}

#[cfg(target_os = "macos")]
#[test]
fn agent_ready_uses_the_idea_plugin_without_a_global_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let codex_home = temp.path().join("codex-home");
    let workspace = temp.path().join("workspace");
    let nested_workspace = workspace.join("module");
    std::fs::create_dir_all(&nested_workspace).expect("nested workspace");

    let active_binary = write_active_kast_for_test(&home, &config_home);
    write_macos_plugin_workspace_metadata_for_cli(
        &workspace,
        &active_binary,
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

    let ready = kast_at(&active_binary, &home, &config_home)
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
        "IDEA-backed readiness must ignore provider and worktree skills: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert!(payload["agentEnvironment"].get("skills").is_none());
    assert!(payload["agentEnvironment"].get("guidance").is_none());
}

#[cfg(target_os = "macos")]
#[test]
fn workspace_resources_do_not_affect_machine_readiness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let active_binary = write_active_kast_for_test(&home, &config_home);
    write_macos_plugin_workspace_metadata_for_cli(
        &workspace,
        &active_binary,
        env!("CARGO_PKG_VERSION"),
    );
    let guidance = workspace.join("AGENTS.local.md");
    let skill = workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(skill.parent().expect("skill parent")).expect("skill parent");
    std::fs::write(&guidance, "legacy guidance").expect("guidance");
    std::fs::write(&skill, "legacy skill").expect("skill");

    let ready = kast_at(&active_binary, &home, &config_home)
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
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert!(payload["agentEnvironment"].get("skills").is_none());
    assert!(payload["agentEnvironment"].get("guidance").is_none());
    assert_eq!(
        std::fs::read_to_string(guidance).expect("guidance"),
        "legacy guidance"
    );
    assert_eq!(
        std::fs::read_to_string(skill).expect("skill"),
        "legacy skill"
    );
}

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
fn idea_backed_readiness_ignores_and_preserves_legacy_workspace_guidance() {
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
    std::fs::write(&guidance, "legacy guidance").expect("guidance");
    std::fs::write(&skill, "legacy skill").expect("skill");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
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

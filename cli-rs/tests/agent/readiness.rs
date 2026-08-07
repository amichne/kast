#[path = "../support/mod.rs"]
mod support;

use support::*;

#[test]
fn doctor_retains_host_supplied_macos_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let active_binary = write_active_kast_for_test(&home, &config_home);
    let receipt_path = install_manifest_path(&home);
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("install receipt"))
            .expect("install receipt JSON");
    receipt["platform"] = "macos-test".into();
    receipt["backends"][0]
        .as_object_mut()
        .expect("backend receipt")
        .remove("runtimeLibsDir");
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("install receipt JSON"),
    )
    .expect("host-supplied backend receipt");

    let ready = kast_at(&active_binary, &home, &config_home)
        .args(["--output", "human", "ready", "--for", "release"])
        .output()
        .expect("release readiness");
    let stdout = String::from_utf8_lossy(&ready.stdout);

    assert!(stdout.contains("Backend indexer"), "{stdout}");
    assert!(stdout.contains("host-supplied"), "{stdout}");
}

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
fn workspace_resources_do_not_affect_machine_readiness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let active_binary = write_active_kast_for_test(&home, &config_home);
    let guidance = workspace.join("AGENTS.local.md");
    let skill = workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(skill.parent().expect("skill parent")).expect("skill parent");
    std::fs::write(&guidance, "legacy guidance").expect("guidance");
    std::fs::write(&skill, "legacy skill").expect("skill");

    let ready = kast_at(&active_binary, &home, &config_home)
        .env_remove("CODEX_HOME")
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("machine ready");

    assert!(
        ready.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert!(payload.get("agentEnvironment").is_none());
    assert_eq!(
        std::fs::read_to_string(guidance).expect("guidance"),
        "legacy guidance"
    );
    assert_eq!(
        std::fs::read_to_string(skill).expect("skill"),
        "legacy skill"
    );
}

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
    if cfg!(target_os = "macos") {
        assert!(repair.contains("open -a 'IntelliJ IDEA'"), "{repair}");
    } else {
        assert!(repair.contains(" setup --workspace-root "), "{repair}");
    }
}

#[cfg(target_os = "macos")]
#[test]
fn agent_ready_rejects_old_codex_home_skill_against_new_cli_dialect() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let codex_home = temp.path().join("codex-home");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
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
        "---\nname: kast\ndescription: stale fixture\n---\nUse `kast agent tools` and `kast agent workflow`.\n",
    )
    .expect("stale Codex-home skill");
    let stale_before = std::fs::read(&stale_skill).expect("stale skill before readiness");
    let shell_skill = home.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(shell_skill.parent().expect("shell skill parent"))
        .expect("shell skill parent");
    std::fs::write(&shell_skill, current_skill_fixture()).expect("shell-installed skill");
    let ancestor_skill = nested_workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(ancestor_skill.parent().expect("ancestor skill parent"))
        .expect("ancestor skill parent");
    std::fs::write(&ancestor_skill, current_skill_fixture()).expect("workspace ancestor skill");

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
        !ready.status.success(),
        "a stale selectable Codex-home skill must block unconditional readiness: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert_eq!(payload["ok"], false, "{payload:#}");
    assert_eq!(
        payload["agentEnvironment"]["binary"]["dialectRevision"], 2,
        "{payload:#}",
    );
    let candidates = payload["agentEnvironment"]["skills"]["candidates"]
        .as_array()
        .expect("skill candidates");
    let stale = candidates
        .iter()
        .find(|candidate| candidate["path"] == stale_skill.display().to_string())
        .unwrap_or_else(|| panic!("missing stale Codex-home skill: {payload:#}"));
    assert_eq!(stale["state"], "user-owned", "{stale:#}");
    assert_eq!(stale["compatible"], false, "{stale:#}");
    assert!(stale["dialectRevision"].is_null(), "{stale:#}");
    assert!(
        stale["repairCommand"]
            .as_str()
            .is_some_and(|command| command.contains("mv ") && command.contains(".incompatible")),
        "{stale:#}",
    );
    assert_eq!(
        std::fs::read(&stale_skill).expect("stale skill after readiness"),
        stale_before,
        "readiness must not rewrite user-owned skill content",
    );
    let shell = candidates
        .iter()
        .find(|candidate| candidate["path"] == shell_skill.display().to_string())
        .unwrap_or_else(|| panic!("missing shell-installed skill: {payload:#}"));
    assert_eq!(shell["source"], "user-home", "{shell:#}");
    assert_eq!(shell["state"], "user-owned", "{shell:#}");
    assert_eq!(shell["compatible"], true, "{shell:#}");
    let ancestor = candidates
        .iter()
        .find(|candidate| candidate["path"] == ancestor_skill.display().to_string())
        .unwrap_or_else(|| panic!("missing workspace-ancestor skill: {payload:#}"));
    assert_eq!(ancestor["source"], "workspace-ancestor", "{ancestor:#}");
    assert_eq!(ancestor["compatible"], true, "{ancestor:#}");
    let plugin_skill = workspace.join(".agents/skills/kast/SKILL.md");
    let plugin = candidates
        .iter()
        .find(|candidate| candidate["path"] == plugin_skill.display().to_string())
        .unwrap_or_else(|| panic!("missing plugin-managed skill: {payload:#}"));
    assert_eq!(plugin["state"], "managed", "{plugin:#}");
    assert_eq!(plugin["compatible"], true, "{plugin:#}");
    assert_eq!(
        payload["agentEnvironment"]["guidance"]["state"], "managed",
        "{payload:#}",
    );
}

#[cfg(target_os = "macos")]
#[test]
fn agent_ready_reports_modified_plugin_resources_without_rewriting_them() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let codex_home = temp.path().join("codex-home");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    write_macos_plugin_workspace_metadata_for_cli(
        &workspace,
        &homebrew_binary,
        env!("CARGO_PKG_VERSION"),
    );
    let guidance = workspace.join("AGENTS.local.md");
    let changed = std::fs::read_to_string(&guidance)
        .expect("managed guidance")
        .replace(
            "Pass `--workspace-root",
            "Use removed `kast agent tools`, then pass `--workspace-root",
        );
    std::fs::write(&guidance, &changed).expect("modified guidance");
    let skill = workspace.join(".agents/skills/kast/SKILL.md");
    let changed_skill = std::fs::read_to_string(&skill)
        .expect("managed skill")
        .replace(
            "Use typed commands such as `kast agent symbol`",
            "Use removed `kast agent tools`, then `kast agent symbol`",
        );
    std::fs::write(&skill, &changed_skill).expect("modified skill");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .env("CODEX_HOME", &codex_home)
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
        "modified guidance must block ready"
    );
    let payload: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("readiness JSON");
    assert_eq!(
        payload["agentEnvironment"]["guidance"]["state"], "modified",
        "{payload:#}",
    );
    assert!(
        payload["agentEnvironment"]["guidance"]["repairCommand"]
            .as_str()
            .is_some_and(|command| command.contains("open -a 'IntelliJ IDEA'")),
        "{payload:#}",
    );
    let modified_skill = payload["agentEnvironment"]["skills"]["candidates"]
        .as_array()
        .expect("skill candidates")
        .iter()
        .find(|candidate| candidate["path"] == skill.display().to_string())
        .unwrap_or_else(|| panic!("missing modified plugin skill: {payload:#}"));
    assert_eq!(modified_skill["state"], "modified", "{modified_skill:#}");
    assert_eq!(modified_skill["compatible"], false, "{modified_skill:#}");
    assert!(
        modified_skill["repairCommand"]
            .as_str()
            .is_some_and(|command| command.contains("open -a 'IntelliJ IDEA'")),
        "{modified_skill:#}",
    );
    assert_eq!(
        std::fs::read_to_string(&guidance).expect("guidance after readiness"),
        changed,
        "readiness must remain read-only",
    );
    assert_eq!(
        std::fs::read_to_string(&skill).expect("skill after readiness"),
        changed_skill,
        "readiness must not replace a modified plugin skill",
    );
}

#[cfg(target_os = "macos")]
fn current_skill_fixture() -> &'static str {
    "---\nname: kast\ndescription: current fixture\nmetadata:\n  kast-cli-dialect-revision: \"2\"\n---\nUse `kast agent verify`.\n"
}

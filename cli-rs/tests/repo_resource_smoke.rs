mod support;

use support::*;

fn assert_removed(output: &std::process::Output, method: &str) -> serde_json::Value {
    assert!(
        !output.status.success(),
        "{method} should be removed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("removed command json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["method"], method, "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    stdout
}

#[cfg(not(target_os = "macos"))]
#[test]
fn setup_installs_add_managed_git_info_exclude_blocks() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let skill_dir = repo.join(".agents/skills");
    let codex_skill_dir = repo.join(".codex/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&repo).expect("repo");
    let init = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );

    let skill = kast(&home, &config_home)
        .current_dir(&repo)
        .args([
            "--output",
            "json",
            "setup",
            "--skill-target-dir",
            skill_dir.to_str().expect("skill path"),
        ])
        .output()
        .expect("setup with skill target");
    assert!(
        skill.status.success(),
        "setup should write git exclude block: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr),
    );
    let skill_stdout: serde_json::Value =
        serde_json::from_slice(&skill.stdout).expect("setup json");
    assert_eq!(skill_stdout["skill"]["gitExclude"]["attempted"], true);
    assert_eq!(skill_stdout["skill"]["gitExclude"]["updated"], true);

    let codex_skill = kast(&home, &config_home)
        .current_dir(&repo)
        .args([
            "--output",
            "json",
            "setup",
            "--skill-target-dir",
            codex_skill_dir.to_str().expect("codex skill path"),
        ])
        .output()
        .expect("setup codex skill");
    assert!(
        codex_skill.status.success(),
        "second setup should preserve existing skill resource: stdout={}, stderr={}",
        String::from_utf8_lossy(&codex_skill.stdout),
        String::from_utf8_lossy(&codex_skill.stderr),
    );
    let codex_skill_stdout: serde_json::Value =
        serde_json::from_slice(&codex_skill.stdout).expect("codex setup json");
    assert_eq!(codex_skill_stdout["skill"]["gitExclude"]["attempted"], true);
    assert_eq!(codex_skill_stdout["skill"]["gitExclude"]["updated"], true);

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(exclude.contains("# >>> kast skill >>>"));
    assert!(exclude.contains(".agents/skills/kast/SKILL.md"));
    assert!(exclude.contains(".codex/skills/kast/SKILL.md"));
    assert!(exclude.contains("# <<< kast skill <<<"));
    assert!(!exclude.contains("# >>> kast instructions >>>"));
    assert!(!exclude.contains("# >>> kast copilot package >>>"));

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest"),
    )
    .expect("manifest json");
    let resources = manifest["repos"][0]["resources"]
        .as_array()
        .expect("resources");
    let resource_kinds = resources
        .iter()
        .map(|resource| resource["kind"].as_str().expect("resource kind"))
        .collect::<Vec<_>>();
    assert!(resource_kinds.contains(&"SKILL"), "{manifest}");
    assert!(resource_kinds.contains(&"AGENT_GUIDANCE"), "{manifest}");
    assert_eq!(
        resource_kinds
            .iter()
            .filter(|kind| **kind == "SKILL")
            .count(),
        2,
        "{manifest}"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn ready_reports_tampered_manifest_backed_skill_resource() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let skill_dir = repo.join(".agents/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&repo).expect("repo");
    let init = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );

    let skill = kast(&home, &config_home)
        .current_dir(&repo)
        .args([
            "--output",
            "json",
            "setup",
            "--skill-target-dir",
            skill_dir.to_str().expect("skill path"),
        ])
        .output()
        .expect("setup with skill target");
    assert!(
        skill.status.success(),
        "install should write manifest-backed skill state: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr),
    );
    std::fs::write(skill_dir.join("kast/SKILL.md"), b"# tampered\n").expect("tamper skill");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("ready");
    assert!(
        !ready.status.success(),
        "ready should fail closed for tampered managed resources: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("ready json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| {
                let issue = issue.as_str().expect("issue");
                issue.contains("SKILL") && issue.contains("output checksum mismatch")
            }),
        "{stdout}"
    );
}

#[test]
fn ready_resolves_relative_managed_paths_under_install_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let runtime_libs = install_root.join("current/lib/backends/headless/current/runtime-libs");
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
            "components": [],
            "managedPaths": ["current/lib/backends/headless"],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("ready");

    assert!(
        ready.status.success(),
        "ready should treat relative managed paths as install-root-relative: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("ready json");
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(stdout["configuration"]["valid"], true, "{stdout}");
    assert_eq!(
        stdout["canonicalDirectory"]["root"],
        install_root.display().to_string(),
        "{stdout}"
    );
    assert_eq!(stdout["binary"]["configuredExists"], true, "{stdout}");
    assert_eq!(
        stdout["binary"]["configuredMatchesRunning"], true,
        "{stdout}"
    );
    assert!(
        !stdout["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning
                .as_str()
                .expect("warning")
                .contains("Managed path is missing")),
        "{stdout}"
    );
}

#[test]
fn ready_reports_invalid_config_without_crashing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(config_home.join("config.toml"), "[paths\ninstallRoot =")
        .expect("invalid config");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");

    assert!(
        !ready.status.success(),
        "ready should return unhealthy status for invalid config: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("ready json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["configuration"]["exists"], true, "{stdout}");
    assert_eq!(stdout["configuration"]["valid"], false, "{stdout}");
    assert!(
        stdout["configuration"]["error"]
            .as_str()
            .expect("configuration error")
            .contains("Config is invalid"),
        "{stdout}"
    );
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue.as_str().expect("issue").contains("Config is invalid")),
        "{stdout}"
    );
}

#[test]
fn agent_workflow_reports_removed_command_without_step_files() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let out_dir = temp.path().join("workflow");
    std::fs::create_dir_all(&home).expect("home");

    let workflow = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "symbol",
            "--dry-run",
            "--out-dir",
            out_dir.to_str().expect("workflow path"),
            "--symbol",
            "Kast",
            "--references",
        ])
        .output()
        .expect("agent workflow symbol dry-run");

    let stdout = assert_removed(&workflow, "agent/workflow");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("replacement commands");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement
                == "kast agent symbol --query <name> --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(
        !out_dir.exists(),
        "removed workflow dry-run must not write step files"
    );
}

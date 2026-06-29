mod support;

use support::*;

#[test]
fn agent_setup_auto_cli_harness_overrides_configured_harness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target_root = temp.path().join("skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        r#"[projectOpen]
agentHarness = "instructions"
"#,
    )
    .expect("config");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "auto",
            "--harness",
            "skill",
            "--target-dir",
            target_root.to_str().expect("target path"),
            "--force",
        ])
        .output()
        .expect("agent setup auto skill");

    assert!(
        install.status.success(),
        "explicit skill harness should override config: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("skill install json");
    assert_eq!(
        stdout["installedAt"],
        target_root.join("kast").display().to_string()
    );
    assert!(target_root.join("kast/SKILL.md").is_file());
    assert!(!target_root.join("kast/README.md").is_file());
}

#[test]
fn copilot_package_install_preserves_existing_github_content() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let github_dir = temp.path().join(".github");
    let workflow = github_dir.join("workflows/ci.yml");
    let instructions = github_dir.join("copilot-instructions.md");
    let extension_customization = github_dir.join("extensions/kast/custom.json");
    std::fs::create_dir_all(workflow.parent().expect("workflow parent")).expect("workflow dir");
    std::fs::create_dir_all(extension_customization.parent().expect("extension parent"))
        .expect("extension dir");
    std::fs::write(&workflow, b"name: CI\n").expect("workflow");
    std::fs::write(&instructions, b"repo guidance\n").expect("instructions");
    std::fs::write(&extension_customization, b"{\"keep\":true}\n").expect("customization");
    std::fs::write(github_dir.join(".kast-copilot-version"), b"stale\n").expect("marker");

    let copilot = kast(&home, &config_home)
        .args([
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");

    assert!(
        copilot.status.success(),
        "install should update packaged resources: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&workflow).expect("workflow"),
        "name: CI\n"
    );
    assert_eq!(
        std::fs::read_to_string(&instructions).expect("instructions"),
        "repo guidance\n"
    );
    assert_eq!(
        std::fs::read_to_string(&extension_customization).expect("customization"),
        "{\"keep\":true}\n"
    );
    assert!(
        !github_dir.join(".kast-copilot-version").exists(),
        "package marker should be removed after manifest-backed refresh"
    );
    assert!(github_dir.join("lsp.json").is_file());
    assert!(
        !github_dir
            .join("instructions/kast-kotlin.instructions.md")
            .exists()
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(!github_dir.join("agents/kast-reader.agent.md").exists());
    assert!(!github_dir.join("agents/kast-writer.agent.md").exists());
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-trace.mjs")
            .is_file()
    );
    assert!(
        github_dir.join("extensions/kast/custom.json").is_file(),
        "unrelated old extension customization should be preserved"
    );
}

#[test]
fn copilot_package_install_adds_managed_git_info_exclude_block() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let github_dir = repo.join(".github");
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

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");

    assert!(
        copilot.status.success(),
        "install should write git exclude block: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(stdout["gitExclude"]["attempted"], true);
    assert_eq!(stdout["gitExclude"]["updated"], true);
    assert_eq!(
        stdout["gitExclude"]["excludeFile"],
        std::fs::canonicalize(&repo)
            .expect("canonical repo")
            .join(".git/info/exclude")
            .display()
            .to_string()
    );
    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest"),
    )
    .expect("manifest json");
    assert_eq!(
        manifest["repos"][0]["path"],
        std::fs::canonicalize(&repo)
            .expect("canonical repo")
            .display()
            .to_string()
    );
    assert_eq!(
        manifest["repos"][0]["resources"][0]["kind"],
        "COPILOT_PACKAGE"
    );
    assert_eq!(
        manifest["repos"][0]["resources"][0]["primitiveVersion"],
        env!("CARGO_PKG_VERSION")
    );
    assert_eq!(
        manifest["repos"][0]["resources"][0]["sourceBundleSha256"]
            .as_str()
            .expect("source bundle checksum")
            .len(),
        64
    );

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(exclude.contains("# >>> kast copilot package >>>"));
    assert!(!exclude.contains(".github/.kast-copilot-version"));
    assert!(exclude.contains(".github/lsp.json"));
    assert!(exclude.contains("# <<< kast copilot package <<<"));

    let retired_catalog = github_dir.join("extensions/kast/_shared/commands.json");
    std::fs::write(&retired_catalog, b"old generated catalog\n").expect("retired catalog");

    let rerun = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("reinstall copilot plugin");
    assert!(
        rerun.status.success(),
        "reinstall should be idempotent: stdout={}, stderr={}",
        String::from_utf8_lossy(&rerun.stdout),
        String::from_utf8_lossy(&rerun.stderr),
    );
    let rerun_stdout: serde_json::Value =
        serde_json::from_slice(&rerun.stdout).expect("copilot reinstall json");
    assert_eq!(rerun_stdout["gitExclude"]["attempted"], true);
    assert_eq!(rerun_stdout["gitExclude"]["updated"], false);
    assert!(
        !retired_catalog.exists(),
        "reinstall should remove retired generated Copilot catalog output"
    );
}

#[test]
fn skill_and_instruction_installs_add_managed_git_info_exclude_blocks() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let skill_dir = repo.join(".agents/skills");
    let codex_skill_dir = repo.join(".codex/skills");
    let instructions_dir = repo.join(".agents/instructions");
    let codex_instructions_dir = repo.join(".codex/instructions");
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
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
        ])
        .output()
        .expect("install skill");
    assert!(
        skill.status.success(),
        "skill install should write git exclude block: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr),
    );
    let skill_stdout: serde_json::Value =
        serde_json::from_slice(&skill.stdout).expect("skill install json");
    assert_eq!(skill_stdout["gitExclude"]["attempted"], true);
    assert_eq!(skill_stdout["gitExclude"]["updated"], true);

    let codex_skill = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            codex_skill_dir.to_str().expect("codex skill path"),
        ])
        .output()
        .expect("install codex skill");
    assert!(
        codex_skill.status.success(),
        "second skill install should preserve existing skill resource: stdout={}, stderr={}",
        String::from_utf8_lossy(&codex_skill.stdout),
        String::from_utf8_lossy(&codex_skill.stderr),
    );
    let codex_skill_stdout: serde_json::Value =
        serde_json::from_slice(&codex_skill.stdout).expect("codex skill install json");
    assert_eq!(codex_skill_stdout["gitExclude"]["attempted"], true);
    assert_eq!(codex_skill_stdout["gitExclude"]["updated"], true);

    let instructions = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "instructions",
            "--target-dir",
            instructions_dir.to_str().expect("instructions path"),
        ])
        .output()
        .expect("install instructions");
    assert!(
        instructions.status.success(),
        "instructions install should write git exclude block: stdout={}, stderr={}",
        String::from_utf8_lossy(&instructions.stdout),
        String::from_utf8_lossy(&instructions.stderr),
    );
    let instructions_stdout: serde_json::Value =
        serde_json::from_slice(&instructions.stdout).expect("instructions install json");
    assert_eq!(instructions_stdout["gitExclude"]["attempted"], true);
    assert_eq!(instructions_stdout["gitExclude"]["updated"], true);

    let codex_instructions = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "instructions",
            "--target-dir",
            codex_instructions_dir
                .to_str()
                .expect("codex instructions path"),
        ])
        .output()
        .expect("install codex instructions");
    assert!(
        codex_instructions.status.success(),
        "second instructions install should preserve existing instructions resource: stdout={}, stderr={}",
        String::from_utf8_lossy(&codex_instructions.stdout),
        String::from_utf8_lossy(&codex_instructions.stderr),
    );
    let codex_instructions_stdout: serde_json::Value =
        serde_json::from_slice(&codex_instructions.stdout)
            .expect("codex instructions install json");
    assert_eq!(codex_instructions_stdout["gitExclude"]["attempted"], true);
    assert_eq!(codex_instructions_stdout["gitExclude"]["updated"], true);

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(exclude.contains("# >>> kast skill >>>"));
    assert!(exclude.contains(".agents/skills/kast/SKILL.md"));
    assert!(exclude.contains(".codex/skills/kast/SKILL.md"));
    assert!(exclude.contains("# <<< kast skill <<<"));
    assert!(exclude.contains("# >>> kast instructions >>>"));
    assert!(exclude.contains(".agents/instructions/kast/README.md"));
    assert!(exclude.contains(".codex/instructions/kast/README.md"));
    assert!(exclude.contains("# <<< kast instructions <<<"));

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest"),
    )
    .expect("manifest json");
    let resources = manifest["repos"][0]["resources"]
        .as_array()
        .expect("resources");
    assert_eq!(resources.len(), 4, "{manifest}");
    let resource_targets = resources
        .iter()
        .map(|resource| resource["targetPath"].as_str().expect("target path"))
        .collect::<std::collections::BTreeSet<_>>();
    assert!(
        resource_targets
            .iter()
            .any(|target| target.ends_with(".agents/skills/kast")),
        "{manifest}"
    );
    assert!(
        resource_targets
            .iter()
            .any(|target| target.ends_with(".codex/skills/kast")),
        "{manifest}"
    );
    assert!(
        resource_targets
            .iter()
            .any(|target| target.ends_with(".agents/instructions/kast")),
        "{manifest}"
    );
    assert!(
        resource_targets
            .iter()
            .any(|target| target.ends_with(".codex/instructions/kast")),
        "{manifest}"
    );
    let resource_kinds = resources
        .iter()
        .map(|resource| resource["kind"].as_str().expect("resource kind"))
        .collect::<Vec<_>>();
    assert_eq!(
        resource_kinds,
        vec!["SKILL", "SKILL", "INSTRUCTIONS", "INSTRUCTIONS"]
    );
}

#[test]
fn copilot_package_install_can_skip_git_info_exclude() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let github_dir = repo.join(".github");
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

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--no-auto-exclude-git",
        ])
        .output()
        .expect("install copilot plugin");

    assert!(
        copilot.status.success(),
        "install should support git exclude opt-out: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(stdout["gitExclude"]["attempted"], false);
    assert_eq!(stdout["gitExclude"]["updated"], false);
    assert_eq!(stdout["gitExclude"]["reason"], "disabled");

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(!exclude.contains("# >>> kast copilot package >>>"));
    assert!(!exclude.contains(".github/lsp.json"));
}

#[test]
fn ready_reports_tampered_manifest_backed_repo_resource() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let github_dir = repo.join(".github");
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

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");
    assert!(
        copilot.status.success(),
        "install should write manifest-backed resource state: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    std::fs::write(github_dir.join("lsp.json"), b"{\"tampered\":true}\n").expect("tamper lsp");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
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
            .any(|issue| issue
                .as_str()
                .expect("issue")
                .contains("COPILOT_PACKAGE output checksum mismatch")),
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
        .args(["--output", "json", "ready"])
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
fn agent_workflow_dry_run_writes_stable_step_files() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let out_dir = temp.path().join("workflow");
    std::fs::create_dir_all(&home).expect("home");

    let workflow = kast(&home, &config_home)
        .args([
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

    assert!(
        workflow.status.success(),
        "workflow dry-run should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow envelope json");
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(stdout["method"], "agent/workflow/symbol", "{stdout}");
    assert_eq!(stdout["result"]["workflow"], "symbol", "{stdout}");
    assert_eq!(stdout["result"]["dryRun"], true, "{stdout}");
    assert!(out_dir.join("workflow.json").is_file());
    assert!(out_dir.join("symbol-query/input.json").is_file());
    assert!(out_dir.join("symbol-query/stdout.json").is_file());
    assert!(out_dir.join("symbol-resolve/input.json").is_file());
    assert!(out_dir.join("symbol-references/input.json").is_file());
}

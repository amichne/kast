mod support;

use support::*;

fn assert_removed_setup_command(output: &std::process::Output, method: &str) -> serde_json::Value {
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

#[test]
fn agent_setup_auto_reports_removed_command_before_resource_selection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target_root = temp.path().join("enterprise-agent");
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
            "--target-dir",
            target_root.to_str().expect("target path"),
            "--force",
        ])
        .output()
        .expect("agent setup auto instructions");

    let stdout = assert_removed_setup_command(&install, "agent/setup/auto");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("replacement commands");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast setup --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(
        !target_root.exists(),
        "removed setup auto must not write portable agent resources"
    );
}

#[test]
fn codex_skill_roots_are_first_class_agent_targets() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let codex_skills = workspace.join(".codex/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&codex_skills).expect("codex skills");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    std::fs::write(
        config_home.join("config.toml"),
        r#"[projectOpen]
agentHarness = "skill"
"#,
    )
    .expect("config");
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
        .expect("ready repair");
    assert!(
        repair.status.success(),
        "repair --apply should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            codex_skills.to_str().expect("codex skills"),
            "--force",
        ])
        .output()
        .expect("agent setup skill");
    assert!(
        install.status.success(),
        "Codex skill root should install through the skill setup path: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let install_stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("skill install json");
    let expected_codex_skill = codex_skills
        .join("kast")
        .canonicalize()
        .expect("canonical installed Codex skill");
    let installed_at = PathBuf::from(
        install_stdout["installedAt"]
            .as_str()
            .expect("installed at"),
    )
    .canonicalize()
    .expect("canonical installed at");
    assert_eq!(installed_at, expected_codex_skill);
    assert!(codex_skills.join("kast/SKILL.md").is_file());
    assert!(!codex_skills.join("kast/references").exists());
    assert!(!codex_skills.join("kast/scripts").exists());

    let up = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--dry-run",
        ])
        .output()
        .expect("agent up dry-run");
    assert!(
        up.status.success(),
        "agent up dry-run should preserve Codex skill target: stdout={}, stderr={}",
        String::from_utf8_lossy(&up.stdout),
        String::from_utf8_lossy(&up.stderr)
    );
    let up_stdout: serde_json::Value = serde_json::from_slice(&up.stdout).expect("up json");
    assert_eq!(
        up_stdout["setup"]["type"], "AGENT_SETUP_PLAN",
        "{up_stdout}"
    );
    assert_eq!(
        PathBuf::from(
            up_stdout["setup"]["skillTarget"]
                .as_str()
                .expect("setup skill target")
        )
        .canonicalize()
        .unwrap_or_else(|_| workspace.join(".agents/skills/kast")),
        workspace.join(".agents/skills/kast"),
        "{up_stdout}"
    );
    let install_command = up_stdout["setup"]["installCommand"]
        .as_array()
        .expect("install command");
    assert_eq!(install_command.len(), 5, "{up_stdout}");
    assert_eq!(
        install_command[0],
        env!("CARGO_BIN_EXE_kast"),
        "{up_stdout}"
    );
    assert_eq!(install_command[1], "agent", "{up_stdout}");
    assert_eq!(install_command[2], "setup", "{up_stdout}");
    assert_eq!(install_command[3], "--workspace-root", "{up_stdout}");
    assert_eq!(
        PathBuf::from(install_command[4].as_str().expect("install command target")),
        workspace,
        "{up_stdout}"
    );

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--skill-root")
        .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/kast-skill"))
        .arg("--kast-bin")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--require-gradle-project")
        .arg("--require-skill")
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run verifier");
    assert!(
        verify.status.success(),
        "verifier should accept manifest-backed Codex skill target: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let verify_json: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("verifier json");
    let codex_target = verify_json["checks"]["skills"]["targets"]
        .as_array()
        .expect("skill targets")
        .iter()
        .find(|target| {
            target["path"]
                .as_str()
                .expect("target path")
                .ends_with(".codex/skills/kast")
        })
        .expect("Codex skill target");
    assert!(codex_target["exists"].as_bool().expect("exists"));
    assert!(
        codex_target["manifestResource"].is_object(),
        "{codex_target:#}"
    );
    assert_eq!(
        codex_target["manifestOutputMismatches"]
            .as_array()
            .expect("manifest output mismatches")
            .len(),
        0
    );
}

#[test]
fn explicit_host_skill_target_is_manifest_backed_outside_workspace_repo() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let host_skill_root = temp.path().join("host-codex/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&host_skill_root).expect("host skill root");
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

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            host_skill_root.to_str().expect("host skill root"),
            "--force",
        ])
        .output()
        .expect("agent setup skill");
    assert!(
        install.status.success(),
        "explicit host skill install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let install_stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("skill install json");
    let installed_at = PathBuf::from(
        install_stdout["installedAt"]
            .as_str()
            .expect("installed at"),
    )
    .canonicalize()
    .expect("canonical installed at");
    let expected_host_skill = host_skill_root
        .join("kast")
        .canonicalize()
        .expect("canonical installed host skill");
    assert_eq!(installed_at, expected_host_skill);
    assert!(expected_host_skill.join("SKILL.md").is_file());
    assert!(!expected_host_skill.join("references").exists());
    assert!(!expected_host_skill.join("scripts").exists());

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--skill-root")
        .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/kast-skill"))
        .arg("--kast-bin")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--require-gradle-project")
        .arg("--require-skill")
        .arg("--skill-target-dir")
        .arg(&host_skill_root)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run verifier");
    assert!(
        verify.status.success(),
        "verifier should accept explicit host skill target: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let verify_json: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("verifier json");
    let host_target = verify_json["checks"]["skills"]["targets"]
        .as_array()
        .expect("skill targets")
        .iter()
        .find(|target| {
            PathBuf::from(target["path"].as_str().expect("target path"))
                .canonicalize()
                .is_ok_and(|path| path == expected_host_skill)
        })
        .expect("host skill target");
    assert!(host_target["exists"].as_bool().expect("exists"));
    assert!(
        host_target["manifestResource"].is_object(),
        "{host_target:#}"
    );
    assert_eq!(
        host_target["manifestOutputMismatches"]
            .as_array()
            .expect("manifest output mismatches")
            .len(),
        0
    );
}

#[test]
fn agent_setup_skill_can_override_packaged_skill_source() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let target_root = temp.path().join("host-codex/skills");
    let source_root = temp.path().join("source-skill");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(source_root.join("references")).expect("source references");
    std::fs::create_dir_all(source_root.join("fixtures/maintenance/evals"))
        .expect("source eval fixtures");
    std::fs::create_dir_all(source_root.join("scripts/__pycache__")).expect("source cache");
    std::fs::write(source_root.join("SKILL.md"), "override skill\n").expect("source skill");
    std::fs::write(
        source_root.join("references/commands.json"),
        r#"{"commands":{}}"#,
    )
    .expect("source commands");
    std::fs::write(
        source_root.join("fixtures/maintenance/evals/routing.json"),
        r#"{"cases":[]}"#,
    )
    .expect("source routing eval");
    std::fs::write(source_root.join(".kast-version"), "retired marker\n").expect("source marker");
    std::fs::write(
        source_root.join("scripts/helper.py"),
        "#!/usr/bin/env python3\n",
    )
    .expect("source script");
    std::fs::write(
        source_root.join("scripts/__pycache__/helper.cpython-314.pyc"),
        "cache\n",
    )
    .expect("source cache file");

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            target_root.to_str().expect("target root"),
            "--source-dir",
            source_root.to_str().expect("source root"),
            "--force",
        ])
        .output()
        .expect("agent setup skill with source override");
    assert!(
        install.status.success(),
        "source override skill install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let installed = target_root.join("kast");
    assert_eq!(
        std::fs::read_to_string(installed.join("SKILL.md")).expect("installed skill"),
        "override skill\n"
    );
    assert!(!installed.join("references").exists());
    assert!(!installed.join("fixtures").exists());
    assert!(!installed.join("scripts").exists());
    assert!(!installed.join(".kast-version").exists());
}

#[test]
fn codex_instruction_roots_do_not_reenable_portable_instruction_assets() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let codex_instructions = workspace.join(".codex/instructions");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&codex_instructions).expect("codex instructions");
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
        .args(["repair", "--apply"])
        .output()
        .expect("ready repair");
    assert!(
        repair.status.success(),
        "repair --apply should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );

    let install = kast(&home, &config_home)
        .current_dir(&workspace)
        .args(["--output", "json", "agent", "setup", "auto", "--force"])
        .output()
        .expect("agent setup auto");
    assert_removed_setup_command(&install, "agent/setup/auto");
    assert!(
        !codex_instructions.join("kast").exists(),
        "removed setup auto must not install portable instruction assets"
    );

    let up = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--dry-run",
        ])
        .output()
        .expect("agent up dry-run");
    assert!(
        up.status.success(),
        "agent up dry-run should preserve Codex instruction target: stdout={}, stderr={}",
        String::from_utf8_lossy(&up.stdout),
        String::from_utf8_lossy(&up.stderr)
    );
    let up_stdout: serde_json::Value = serde_json::from_slice(&up.stdout).expect("up json");
    assert_eq!(
        up_stdout["setup"]["type"], "AGENT_SETUP_PLAN",
        "{up_stdout}"
    );
    assert_eq!(
        up_stdout["setup"]["skillTarget"],
        workspace.join(".agents/skills/kast").display().to_string(),
        "{up_stdout}"
    );
}

#[test]
fn packaged_verifier_accepts_explicit_skill_target_dirs() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let skill_target_dir = workspace.join("host-agent/skills");
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
        .args(["repair", "--apply"])
        .output()
        .expect("ready repair");
    assert!(
        repair.status.success(),
        "repair --apply should converge: stdout={}, stderr={}",
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

    let verifier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/verify-kast-state.py");
    let verify = Command::new("python3")
        .arg(&verifier)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--skill-root")
        .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/kast-skill"))
        .arg("--kast-bin")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--require-gradle-project")
        .arg("--require-skill")
        .arg("--skill-target-dir")
        .arg(&skill_target_dir)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run verifier");
    assert!(
        verify.status.success(),
        "verifier should accept explicit manifest-backed resource targets: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let verify_json: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("verifier json");
    let skill_target = verify_json["checks"]["skills"]["targets"]
        .as_array()
        .expect("skill targets")
        .iter()
        .find(|target| {
            target["path"]
                .as_str()
                .expect("target path")
                .ends_with("host-agent/skills/kast")
        })
        .expect("explicit skill target");
    assert!(skill_target["exists"].as_bool().expect("exists"));
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
}

#[test]
fn github_instruction_root_reports_removed_auto_setup_without_writing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let github_instructions = workspace.join(".github/instructions");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&github_instructions).expect("github instructions");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args(["--output", "json", "agent", "setup", "auto", "--dry-run"])
        .output()
        .expect("agent setup auto dry-run");

    assert_removed_setup_command(&plan, "agent/setup/auto");
    assert!(
        !github_instructions.join("kast").exists(),
        "removed setup auto must not install instructions"
    );
}

#[test]
fn plain_github_repo_reports_removed_auto_setup_without_copilot_assets() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(workspace.join(".github/workflows")).expect("github workflows");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args(["--output", "json", "agent", "setup", "auto", "--dry-run"])
        .output()
        .expect("agent setup auto dry-run");

    assert_removed_setup_command(&plan, "agent/setup/auto");
    assert!(
        !workspace.join(".github/lsp.json").exists(),
        "removed setup auto must not install the Copilot package"
    );
}

#[test]
fn agent_setup_auto_dry_run_reports_removed_command_without_writing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target_root = temp.path().join("enterprise-agent");
    let alternate_bin = temp.path().join("enterprise-kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &alternate_bin).expect("copy kast");
    set_executable_for_test(&alternate_bin);
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        r#"[projectOpen]
agentHarness = "instructions"
"#,
    )
    .expect("config");

    let plan = Command::new(&alternate_bin)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "auto",
            "--target-dir",
            target_root.to_str().expect("target path"),
            "--dry-run",
        ])
        .output()
        .expect("agent setup auto dry-run");

    let stdout = assert_removed_setup_command(&plan, "agent/setup/auto");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("replacement commands");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast setup --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(!target_root.exists(), "dry-run must not write target files");
}

#[test]
fn agent_setup_concrete_parent_dry_run_only_plans_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let cases = [(
        "skill",
        workspace.join("agent/skills"),
        workspace.join("agent/skills/kast/SKILL.md"),
    )];

    for (command, target_dir, expected_output) in cases {
        let plan = kast(&home, &config_home)
            .current_dir(&workspace)
            .args([
                "--output",
                "json",
                "agent",
                "setup",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
                "--dry-run",
                command,
                "--target-dir",
                target_dir.to_str().expect("target dir"),
                "--force",
            ])
            .output()
            .unwrap_or_else(|error| panic!("agent setup {command} dry-run: {error}"));

        assert!(
            plan.status.success(),
            "agent setup {command} parent dry-run should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr)
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&plan.stdout).expect("setup plan json");
        assert_eq!(stdout["harness"], command, "{stdout}");
        assert_eq!(stdout["selectionSource"], "explicit", "{stdout}");
        assert_eq!(stdout["dryRun"], true, "{stdout}");
        assert_eq!(
            stdout["targetDir"],
            target_dir.display().to_string(),
            "{stdout}"
        );
        let install_command = stdout["installCommand"]
            .as_array()
            .expect("install command");
        assert!(install_command.iter().any(|arg| arg == command), "{stdout}");
        assert!(
            !install_command.iter().any(|arg| arg == "--dry-run"),
            "planned install command should omit dry-run: {stdout}"
        );
        assert!(
            !expected_output.exists(),
            "agent setup {command} parent dry-run must not write {}",
            expected_output.display()
        );
    }

    assert!(
        !install_manifest_path(&home).exists(),
        "dry-run must not record manifest resources"
    );
}

#[test]
fn agent_setup_concrete_removed_asset_subcommands_report_removed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let cases = [
        (
            "instructions",
            "agent/setup/instructions",
            workspace.join("agent/instructions"),
            workspace.join("agent/instructions/kast/README.md"),
        ),
        (
            "copilot",
            "agent/setup/copilot",
            workspace.join("agent/github"),
            workspace.join("agent/github/lsp.json"),
        ),
    ];

    for (command, method, target_dir, expected_output) in cases {
        let removed = kast(&home, &config_home)
            .current_dir(&workspace)
            .args([
                "--output",
                "json",
                "agent",
                "setup",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
                "--dry-run",
                command,
                "--target-dir",
                target_dir.to_str().expect("target dir"),
                "--force",
            ])
            .output()
            .unwrap_or_else(|error| panic!("agent setup {command} dry-run: {error}"));

        assert_removed_setup_command(&removed, method);
        assert!(
            !expected_output.exists(),
            "agent setup {command} must not write {}",
            expected_output.display()
        );
    }

    assert!(
        !install_manifest_path(&home).exists(),
        "removed commands must not record manifest resources"
    );
}

#[test]
fn agent_setup_concrete_subcommand_dry_run_does_not_install_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let target_dir = workspace.join("agent/skills");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            target_dir.to_str().expect("target dir"),
            "--force",
            "--dry-run",
        ])
        .output()
        .expect("agent setup skill subcommand dry-run");

    assert!(
        plan.status.success(),
        "agent setup skill subcommand dry-run should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("setup plan json");
    assert_eq!(stdout["harness"], "skill", "{stdout}");
    assert_eq!(stdout["dryRun"], true, "{stdout}");
    assert!(
        !target_dir.join("kast/SKILL.md").exists(),
        "subcommand dry-run must not install the skill"
    );
    assert!(
        !install_manifest_path(&home).exists(),
        "subcommand dry-run must not record manifest resources"
    );
}

#[test]
fn agent_up_dry_run_uses_guidance_setup_and_explicit_workspace_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let alternate_bin = temp.path().join("enterprise-kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &alternate_bin).expect("copy kast");
    set_executable_for_test(&alternate_bin);
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    std::fs::write(
        config_home.join("config.toml"),
        r#"[projectOpen]
agentHarness = "skill"
"#,
    )
    .expect("config");

    let plan = Command::new(&alternate_bin)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .current_dir(temp.path())
        .args([
            "--output",
            "json",
            "agent",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "headless",
            "--dry-run",
        ])
        .output()
        .expect("agent up dry-run");

    assert!(
        plan.status.success(),
        "agent up dry-run should succeed without writing files: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&plan.stdout).expect("agent up plan json");
    assert_eq!(stdout["type"], "AGENT_UP", "{stdout}");
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(stdout["stage"], "DRY_RUN", "{stdout}");
    assert_eq!(stdout["dryRun"], true, "{stdout}");
    assert_eq!(stdout["setup"]["type"], "AGENT_SETUP_PLAN", "{stdout}");
    assert_eq!(stdout["setup"]["dryRun"], true, "{stdout}");
    assert_eq!(
        stdout["setup"]["skillTarget"],
        workspace.join(".agents/skills/kast").display().to_string(),
        "{stdout}"
    );
    assert_eq!(
        stdout["setup"]["installCommand"],
        serde_json::json!([
            alternate_bin.display().to_string(),
            "agent",
            "setup",
            "--workspace-root",
            workspace.display().to_string()
        ]),
        "{stdout}"
    );
    assert_eq!(
        stdout["runtimeCommand"],
        serde_json::json!([
            alternate_bin.display().to_string(),
            "runtime",
            "up",
            "--workspace-root",
            workspace.display().to_string(),
            "--backend",
            "headless"
        ]),
        "{stdout}"
    );
    assert_eq!(
        stdout["nextActions"][0]["label"], "Run repository bring-up",
        "{stdout}"
    );
    assert_eq!(
        stdout["nextActions"][0]["argv"],
        serde_json::json!([
            alternate_bin.display().to_string(),
            "agent",
            "up",
            "--workspace-root",
            workspace.display().to_string(),
            "--backend",
            "headless"
        ]),
        "{stdout}"
    );
    assert!(
        !workspace.join(".agents/skills").exists(),
        "agent up dry-run must not write setup files"
    );
}

mod support;

use support::*;

#[cfg(not(target_os = "macos"))]
fn assert_compact_kast_guidance(content: &str) {
    assert!(content.contains("<kast>"), "{content}");
    assert!(
        content.contains("SKILL.md` before Kotlin or Gradle semantic work"),
        "{content}"
    );
    assert!(
        content.contains("`kast agent verify --workspace-root \"$PWD\"`"),
        "{content}"
    );
    assert!(
        content.contains("`kast agent symbol --query <name>`"),
        "{content}"
    );
    assert!(
        content.contains("`kast agent rename --symbol <fq-name> --new-name <name> --apply`"),
        "{content}"
    );
    assert!(content.contains("`kast repair --apply`"), "{content}");
    assert!(
        !content.contains("When a user or agent asks for anything regarding Kotlin code"),
        "{content}"
    );
    assert!(
        !content.contains("grep, ripgrep, regex search, raw text search"),
        "{content}"
    );
    let managed = content
        .split_once("<kast>")
        .and_then(|(_, rest)| rest.split_once("</kast>").map(|(managed, _)| managed))
        .unwrap_or(content);
    let managed_lines = managed
        .lines()
        .filter(|line| {
            !line.is_empty()
                && !line.starts_with("<kast")
                && !line.starts_with("</kast>")
                && !line.starts_with("<!--")
        })
        .count();
    assert!(
        (4..=5).contains(&managed_lines),
        "Kast guidance should stay a 4-5 line routing aid, got {managed_lines}: {content}"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn agent_setup_installs_skill_and_writes_ignored_local_guidance() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
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
    std::fs::write(
        repo.join("AGENTS.md"),
        "# Repo guidance\n\nKeep local text.\n",
    )
    .expect("agents");

    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup", "--no-open-ide"])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["type"], "AGENT_SETUP", "{stdout}");
    assert_eq!(
        PathBuf::from(
            stdout["skill"]["installedAt"]
                .as_str()
                .expect("skill target")
        )
        .canonicalize()
        .expect("canonical installed skill"),
        repo.join(".agents/skills/kast")
            .canonicalize()
            .expect("canonical expected skill")
    );
    assert!(repo.join(".agents/skills/kast/SKILL.md").is_file());
    let root_agents = std::fs::read_to_string(repo.join("AGENTS.md")).expect("agents");
    assert!(root_agents.starts_with("# Repo guidance\n\nKeep local text.\n"));
    assert_compact_kast_guidance(&root_agents);
    assert!(!repo.join("AGENTS.local.md").exists());
    let attributes =
        std::fs::read_to_string(repo.join(".git/info/attributes")).expect("git attributes");
    assert!(
        attributes.contains("AGENTS.md filter=kast-context-region"),
        "{attributes}"
    );
    assert!(!repo.join(".github/lsp.json").exists());
    assert!(!repo.join(".github/extensions/kast/extension.mjs").exists());

    let manifest = std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest");
    assert!(
        manifest.contains("\"kind\": \"AGENT_GUIDANCE\""),
        "{manifest}"
    );
    assert!(
        manifest.contains("\"region\": \"KAST_MANAGED_FENCE\""),
        "{manifest}"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn agent_setup_context_git_filter_strips_managed_region_for_each_tracked_target() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo with spaces");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
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
    std::fs::write(repo.join("AGENTS.md"), "# Agents\n").expect("agents");

    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args([
            "--output",
            "json",
            "setup",
            "--no-open-ide",
            "--context-file",
            "CODEX.md",
        ])
        .output()
        .expect("setup");
    assert!(
        setup.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let attributes =
        std::fs::read_to_string(repo.join(".git/info/attributes")).expect("attributes");
    assert!(
        attributes.contains("AGENTS.md filter=kast-context-region"),
        "{attributes}"
    );
    assert!(
        attributes.contains("CODEX.md filter=kast-context-region"),
        "{attributes}"
    );

    let add = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .args(["add", "AGENTS.md", "CODEX.md"])
        .output()
        .expect("git add context files");
    assert!(
        add.status.success(),
        "git add failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&add.stdout),
        String::from_utf8_lossy(&add.stderr)
    );
    for file in ["AGENTS.md", "CODEX.md"] {
        let show = Command::new("git")
            .arg("-C")
            .arg(&repo)
            .args(["show", &format!(":{file}")])
            .output()
            .unwrap_or_else(|error| panic!("git show {file}: {error}"));
        assert!(
            show.status.success(),
            "git show {file} failed: stdout={}, stderr={}",
            String::from_utf8_lossy(&show.stdout),
            String::from_utf8_lossy(&show.stderr)
        );
        let staged = String::from_utf8_lossy(&show.stdout);
        assert!(
            !staged.contains("<kast"),
            "clean filter should remove managed region from staged {file}: {staged}"
        );
        assert!(
            !staged.contains("</kast>"),
            "clean filter should remove managed region from staged {file}: {staged}"
        );
        if file == "AGENTS.md" {
            assert_eq!(
                staged, "# Agents\n",
                "clean filter should not leave trailing blank lines after removing the managed region"
            );
        }
    }
}

#[cfg(target_os = "macos")]
#[test]
fn setup_fails_closed_on_macos_without_writing_partial_workspace_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");
    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup"])
        .output()
        .expect("setup");

    assert!(
        !setup.status.success(),
        "setup should fail closed on macOS: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    assert!(
        !repo.join(".agents/skills/kast/SKILL.md").exists(),
        "setup must not install skill-only partial state on macOS"
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    assert!(
        stdout["error"]["message"]
            .as_str()
            .expect("message")
            .contains("IntelliJ plugin"),
        "{stdout}"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn agent_setup_creates_local_guidance_without_root_agents_md() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");

    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup", "--no-open-ide"])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup should succeed without root AGENTS.md: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    assert!(repo.join(".agents/skills/kast/SKILL.md").is_file());
    assert!(
        !repo.join("AGENTS.md").exists(),
        "default setup must not create root AGENTS.md"
    );
    assert!(
        repo.join("AGENTS.local.md").is_file(),
        "default setup should create local agent guidance"
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(
        stdout["agentsMdTargets"]
            .as_array()
            .expect("agents targets")
            .len(),
        1,
        "{stdout}"
    );
    assert_eq!(
        PathBuf::from(
            stdout["agentsMdTargets"][0]["path"]
                .as_str()
                .expect("local guidance target")
        )
        .canonicalize()
        .expect("canonical local guidance target"),
        repo.join("AGENTS.local.md")
            .canonicalize()
            .expect("canonical expected local guidance"),
        "{stdout}"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn agent_setup_creates_explicit_agents_md_target() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let scoped_agents = repo.join("module/AGENTS.md");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");

    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args([
            "--output",
            "json",
            "setup",
            "--no-open-ide",
            "--agents-md",
            scoped_agents.to_str().expect("agents path"),
        ])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "explicit AGENTS.md target should be created: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let content = std::fs::read_to_string(&scoped_agents).expect("scoped agents");
    assert_compact_kast_guidance(&content);
    assert!(repo.join("AGENTS.local.md").is_file());
}

#[cfg(not(target_os = "macos"))]
#[test]
fn agent_setup_backs_up_and_repairs_modified_managed_region() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");
    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup", "--no-open-ide"])
        .output()
        .expect("setup");
    assert!(
        setup.status.success(),
        "initial setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let agents_path = repo.join("AGENTS.local.md");
    let mut content = std::fs::read_to_string(&agents_path).expect("agents");
    content = content.replace("Kast routing", "Custom routing");
    std::fs::write(&agents_path, content).expect("tamper agents");

    let repaired_setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup", "--no-open-ide"])
        .output()
        .expect("setup repair");
    assert!(
        repaired_setup.status.success(),
        "modified managed region should be backed up and repaired: stdout={}, stderr={}",
        String::from_utf8_lossy(&repaired_setup.stdout),
        String::from_utf8_lossy(&repaired_setup.stderr)
    );
    let repaired = std::fs::read_to_string(&agents_path).expect("repaired agents");
    assert!(
        repaired.contains("Kast routing") && repaired.contains("`kast agent verify"),
        "{repaired}"
    );
    let backup_exists = std::fs::read_dir(&repo)
        .expect("repo entries")
        .any(|entry| {
            entry
                .expect("entry")
                .file_name()
                .to_string_lossy()
                .contains("kast-backup")
        });
    assert!(
        backup_exists,
        "setup should preserve a backup before repairing"
    );
}

#[cfg(not(target_os = "macos"))]
#[test]
fn setup_leaves_existing_hook_config_out_of_scope() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(repo.join(".codex")).expect("codex");
    std::fs::create_dir_all(repo.join(".claude")).expect("claude");
    std::fs::write(
        repo.join(".codex/hooks.json"),
        serde_json::to_string_pretty(&serde_json::json!({
            "hooks": [{
                "event": "SessionStart",
                "command": ["existing-tool", "--flag"]
            }]
        }))
        .expect("codex json"),
    )
    .expect("codex hooks");
    std::fs::write(
        repo.join(".claude/settings.json"),
        serde_json::to_string_pretty(&serde_json::json!({
            "permissions": {
                "allow": ["Bash(./gradlew test)"]
            },
            "hooks": {
                "SessionStart": [{
                    "hooks": [{
                        "type": "command",
                        "command": "existing-tool --flag"
                    }]
                }]
            }
        }))
        .expect("claude json"),
    )
    .expect("claude settings");

    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "setup", "--no-open-ide"])
        .output()
        .expect("setup");
    assert!(
        setup.status.success(),
        "setup should leave hook config untouched: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );

    let codex: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(repo.join(".codex/hooks.json")).unwrap())
            .expect("codex hooks json");
    let codex_hooks = codex["hooks"].as_array().expect("codex hooks array");
    assert!(
        codex_hooks
            .iter()
            .any(|hook| hook["command"] == serde_json::json!(["existing-tool", "--flag"])),
        "{codex:#}"
    );
    assert_eq!(codex_hooks.len(), 1, "{codex:#}");

    let claude: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(repo.join(".claude/settings.json")).unwrap())
            .expect("claude settings json");
    assert_eq!(
        claude["permissions"]["allow"][0], "Bash(./gradlew test)",
        "{claude:#}"
    );
    let session_hooks = claude["hooks"]["SessionStart"]
        .as_array()
        .expect("claude SessionStart hooks");
    assert!(
        session_hooks.iter().any(|entry| {
            entry["hooks"].as_array().is_some_and(|hooks| {
                hooks
                    .iter()
                    .any(|hook| hook["command"] == "existing-tool --flag")
            })
        }),
        "{claude:#}"
    );
    assert_eq!(session_hooks.len(), 1, "{claude:#}");
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert!(
        stdout
            .get("hookTargets")
            .and_then(|targets| targets.as_array())
            .is_none_or(|targets| targets.is_empty()),
        "{stdout:#}"
    );
}

#[test]
fn agent_tools_reports_removed_command_through_invoked_binary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let alternate_bin = temp.path().join("custom-kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &alternate_bin).expect("copy kast");
    set_executable_for_test(&alternate_bin);

    let agent_tools = Command::new(&alternate_bin)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["--output", "json", "agent", "tools"])
        .output()
        .expect("agent tools");
    assert!(
        !agent_tools.status.success(),
        "agent tools should report removed command: stdout={}, stderr={}",
        String::from_utf8_lossy(&agent_tools.stdout),
        String::from_utf8_lossy(&agent_tools.stderr)
    );

    let stdout: serde_json::Value =
        serde_json::from_slice(&agent_tools.stdout).expect("agent tools removal json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["method"], "agent/tools", "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    assert!(
        stdout["error"]["details"]["replacements"]
            .as_array()
            .expect("replacements")
            .iter()
            .any(|replacement| replacement == "kast help agent"),
        "{stdout}"
    );
}

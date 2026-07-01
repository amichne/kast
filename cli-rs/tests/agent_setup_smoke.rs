mod support;

use support::*;

fn assert_compact_kast_guidance(content: &str) {
    assert!(
        content.contains(
            r#"<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">"#
        ),
        "{content}"
    );
    assert!(
        content.contains("Use `.agents/skills/kast/SKILL.md` and `kast agent`"),
        "{content}"
    );
    assert!(
        content.contains("`kast agent workflow verify --workspace-root \"$PWD\"`"),
        "{content}"
    );
    assert!(
        content.contains("`kast agent workflow package-verify --workspace-root \"$PWD\"`"),
        "{content}"
    );
    assert!(
        !content.contains("When a user or agent asks for anything regarding Kotlin code"),
        "{content}"
    );
    assert!(
        !content.contains("grep, ripgrep, regex search, raw text search"),
        "{content}"
    );
    let managed_lines = content
        .lines()
        .filter(|line| {
            !line.is_empty()
                && !line.starts_with("<kast ")
                && !line.starts_with("</kast>")
                && !line.starts_with("<!--")
        })
        .count();
    assert!(
        (4..=5).contains(&managed_lines),
        "Kast guidance should stay a 4-5 line routing aid, got {managed_lines}: {content}"
    );
}

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
        .args(["--output", "json", "agent", "setup"])
        .output()
        .expect("agent setup");

    assert!(
        setup.status.success(),
        "agent setup should succeed: stdout={}, stderr={}",
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
    assert_eq!(root_agents, "# Repo guidance\n\nKeep local text.\n");
    let local_agents = std::fs::read_to_string(repo.join("AGENTS.local.md")).expect("local agents");
    assert_compact_kast_guidance(&local_agents);
    let exclude = std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git exclude");
    assert!(exclude.contains("AGENTS.local.md"), "{exclude}");
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
        .args(["--output", "json", "agent", "setup"])
        .output()
        .expect("agent setup");

    assert!(
        setup.status.success(),
        "agent setup should succeed without root AGENTS.md: stdout={}, stderr={}",
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
            "agent",
            "setup",
            "--agents-md",
            scoped_agents.to_str().expect("agents path"),
        ])
        .output()
        .expect("agent setup");

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

#[test]
fn agent_setup_rejects_modified_managed_agents_md_region_without_force() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");
    let setup = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "agent", "setup"])
        .output()
        .expect("agent setup");
    assert!(
        setup.status.success(),
        "initial setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let agents_path = repo.join("AGENTS.local.md");
    let mut content = std::fs::read_to_string(&agents_path).expect("agents");
    content = content.replace(
        "Use `.agents/skills/kast/SKILL.md` and `kast agent`",
        "Use `custom/SKILL.md`",
    );
    std::fs::write(&agents_path, content).expect("tamper agents");

    let rejected = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "agent", "setup"])
        .output()
        .expect("agent setup rejected");
    assert!(
        !rejected.status.success(),
        "modified managed region should fail without --force: stdout={}, stderr={}",
        String::from_utf8_lossy(&rejected.stdout),
        String::from_utf8_lossy(&rejected.stderr)
    );
    assert!(
        String::from_utf8_lossy(&rejected.stderr).contains("INSTALL_MANAGED_OUTPUT_MODIFIED"),
        "stderr={}",
        String::from_utf8_lossy(&rejected.stderr)
    );

    let forced = kast(&home, &config_home)
        .current_dir(&repo)
        .args(["--output", "json", "agent", "setup", "--force"])
        .output()
        .expect("agent setup force");
    assert!(
        forced.status.success(),
        "--force should replace only the managed region: stdout={}, stderr={}",
        String::from_utf8_lossy(&forced.stdout),
        String::from_utf8_lossy(&forced.stderr)
    );
    let repaired = std::fs::read_to_string(&agents_path).expect("repaired agents");
    assert!(
        repaired.contains("Use `.agents/skills/kast/SKILL.md` and `kast agent`"),
        "{repaired}"
    );
}

#[test]
fn agent_tools_invocation_argv_uses_invoked_binary_path() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let alternate_bin = temp.path().join("custom-kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &alternate_bin).expect("copy kast");
    set_executable_for_test(&alternate_bin);

    let agent_tools = Command::new(&alternate_bin)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["agent", "tools"])
        .output()
        .expect("agent tools");
    assert!(
        agent_tools.status.success(),
        "agent tools should succeed through alternate binary: stdout={}, stderr={}",
        String::from_utf8_lossy(&agent_tools.stdout),
        String::from_utf8_lossy(&agent_tools.stderr)
    );

    let stdout: serde_json::Value =
        serde_json::from_slice(&agent_tools.stdout).expect("agent tools json");
    assert_eq!(stdout["result"]["invocation"]["command"], "kast agent call");
    assert_eq!(
        stdout["result"]["invocation"]["argv"],
        serde_json::json!([
            alternate_bin.display().to_string(),
            "agent",
            "call",
            "<method>",
        ])
    );
}

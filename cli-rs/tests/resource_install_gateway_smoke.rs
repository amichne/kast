mod support;

use support::*;

#[test]
fn install_resource_gateways_support_force_and_current_versions() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let skill_dir = temp.path().join("skills");
    let instructions_dir = temp.path().join("instructions");
    let github_dir = temp.path().join(".github");
    let stale_skill = skill_dir.join("kast");
    let stale_instructions = instructions_dir.join("kast");
    std::fs::create_dir_all(&home).expect("home");
    let init = Command::new("git")
        .arg("-C")
        .arg(temp.path())
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );
    std::fs::create_dir_all(&stale_skill).expect("stale skill");
    std::fs::create_dir_all(&stale_instructions).expect("stale instructions");
    std::fs::write(stale_skill.join(".kast-version"), b"old\n").expect("stale marker");
    std::fs::write(stale_instructions.join(".kast-version"), b"old\n")
        .expect("stale instructions marker");

    let skill = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "-f",
        ])
        .output()
        .expect("install skill");
    assert!(
        skill.status.success(),
        "skill install should accept -f: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr)
    );
    let skill_stdout: serde_json::Value =
        serde_json::from_slice(&skill.stdout).expect("skill install json");
    assert!(stale_skill.join("SKILL.md").is_file());
    assert_eq!(
        skill_stdout["sourceBundleSha256"]
            .as_str()
            .expect("skill source checksum")
            .len(),
        64
    );
    assert!(
        skill_stdout["outputPaths"]
            .as_array()
            .expect("skill output paths")
            .iter()
            .any(|path| path.as_str().expect("path").ends_with("SKILL.md"))
    );

    let forced_skill = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "-f",
        ])
        .output()
        .expect("force reinstall skill");
    assert!(
        forced_skill.status.success(),
        "skill force reinstall should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&forced_skill.stdout),
        String::from_utf8_lossy(&forced_skill.stderr)
    );
    let forced_skill_stdout: serde_json::Value =
        serde_json::from_slice(&forced_skill.stdout).expect("forced skill json");
    assert_eq!(forced_skill_stdout["skipped"], false);

    assert!(!stale_skill.join(".kast-version").exists());

    let instructions = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "instructions",
            "--target-dir",
            instructions_dir.to_str().expect("instructions path"),
            "-f",
        ])
        .output()
        .expect("install instructions");
    assert!(
        instructions.status.success(),
        "instructions install should accept -f: stdout={}, stderr={}",
        String::from_utf8_lossy(&instructions.stdout),
        String::from_utf8_lossy(&instructions.stderr)
    );
    let instructions_stdout: serde_json::Value =
        serde_json::from_slice(&instructions.stdout).expect("instructions install json");
    assert_eq!(
        instructions_stdout["installedAt"],
        stale_instructions.display().to_string()
    );
    assert!(stale_instructions.join("README.md").is_file());
    assert!(stale_instructions.join("cli.md").is_file());
    assert!(stale_instructions.join("tools.md").is_file());
    assert!(stale_instructions.join("lsp.md").is_file());
    assert!(!stale_instructions.join("rpc.md").exists());
    assert_eq!(
        instructions_stdout["sourceBundleSha256"]
            .as_str()
            .expect("instructions source checksum")
            .len(),
        64
    );
    assert!(!stale_instructions.join(".kast-version").exists());

    std::fs::create_dir_all(github_dir.join("agents")).expect("stale agents dir");
    std::fs::create_dir_all(github_dir.join("instructions")).expect("stale instructions dir");
    std::fs::create_dir_all(github_dir.join("extensions/kast/_shared"))
        .expect("stale extension dir");
    std::fs::write(
        github_dir.join("instructions/kast-kotlin.instructions.md"),
        b"old instructions\n",
    )
    .expect("stale instructions");
    std::fs::write(
        github_dir.join("agents/kast-reader.agent.md"),
        b"old reader\n",
    )
    .expect("stale reader");
    std::fs::write(
        github_dir.join("agents/kast-writer.agent.md"),
        b"old writer\n",
    )
    .expect("stale writer");
    std::fs::write(
        github_dir.join("extensions/kast/_shared/kast-agents.mjs"),
        b"old agents\n",
    )
    .expect("stale agent module");

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--force",
        ])
        .output()
        .expect("install copilot");
    assert!(
        copilot.status.success(),
        "copilot install should accept --force: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr)
    );
    let copilot_stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(
        copilot_stdout["installedAt"],
        github_dir.display().to_string()
    );
    assert!(github_dir.join("lsp.json").is_file());
    assert!(
        !github_dir
            .join("instructions/kast-kotlin.instructions.md")
            .exists()
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-tools.mjs")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-trace.mjs")
            .is_file()
    );
    assert!(
        !github_dir
            .join("extensions/kast/_shared/kast-agents.mjs")
            .exists()
    );
    assert!(
        !github_dir
            .join("instructions/kast-kotlin.instructions.md")
            .exists()
    );
    assert!(!github_dir.join("agents/kast-reader.agent.md").exists());
    assert!(!github_dir.join("agents/kast-writer.agent.md").exists());
    assert_eq!(
        copilot_stdout["sourceBundleSha256"]
            .as_str()
            .expect("copilot source checksum")
            .len(),
        64
    );
    assert!(
        copilot_stdout["outputPaths"]
            .as_array()
            .expect("copilot output paths")
            .iter()
            .any(|path| path
                .as_str()
                .expect("path")
                .ends_with("extensions/kast/extension.mjs"))
    );

    assert!(!github_dir.join(".kast-copilot-version").exists());
    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest"),
    )
    .expect("manifest json");
    let resource_kinds = manifest["repos"]
        .as_array()
        .expect("repos")
        .iter()
        .flat_map(|repo| repo["resources"].as_array().into_iter().flatten())
        .map(|resource| resource["kind"].as_str().expect("kind"))
        .collect::<std::collections::BTreeSet<_>>();
    assert!(resource_kinds.contains("SKILL"), "{manifest}");
    assert!(resource_kinds.contains("INSTRUCTIONS"), "{manifest}");
    assert!(resource_kinds.contains("COPILOT_PACKAGE"), "{manifest}");
}

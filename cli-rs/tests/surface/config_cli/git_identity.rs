fn run_with_poisoned_git_environment(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    selected_repository: &Path,
    args: &[&str],
) -> std::process::Output {
    let git_dir = selected_repository.join(".git");
    kast(home, config_home)
        .args(["--output", "json", "config"])
        .args(args)
        .args([
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .env("GIT_DIR", &git_dir)
        .env("GIT_WORK_TREE", selected_repository)
        .env("GIT_COMMON_DIR", &git_dir)
        .env("GIT_INDEX_FILE", git_dir.join("index"))
        .output()
        .expect("config command with poisoned Git environment")
}

#[test]
fn poisoned_git_environment_cannot_authorize_or_persist_dangling_worktree_consent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let selected_repository = temp.path().join("selected-repository");
    let dangling_worktree = temp.path().join("dangling-worktree");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&selected_repository).expect("selected repository");
    std::fs::create_dir_all(&dangling_worktree).expect("dangling worktree");
    std::fs::write(selected_repository.join("settings.gradle.kts"), "")
        .expect("selected Gradle marker");
    std::fs::write(dangling_worktree.join("settings.gradle.kts"), "")
        .expect("dangling Gradle marker");
    let git = std::process::Command::new("git")
        .args(["init", "--quiet"])
        .current_dir(&selected_repository)
        .status()
        .expect("git init");
    assert!(git.success(), "git init failed: {git}");

    let enabled = run(
        &home,
        &config_home,
        &selected_repository,
        &["set", "codex.hooks.autoStartIndexer", "true"],
    );
    assert!(enabled.status.success(), "enable consent: {enabled:?}");
    let enabled: serde_json::Value =
        serde_json::from_slice(&enabled.stdout).expect("enabled consent JSON");
    let config_path = PathBuf::from(enabled["configPath"].as_str().expect("config path"));
    let enabled_contents = std::fs::read_to_string(&config_path).expect("enabled consent file");
    std::fs::write(
        dangling_worktree.join(".git"),
        "gitdir: ../common/.git/worktrees/missing\n",
    )
    .expect("dangling linked-worktree metadata");

    let listed = run_with_poisoned_git_environment(
        &home,
        &config_home,
        &dangling_worktree,
        &selected_repository,
        &["list"],
    );
    let disabled = run_with_poisoned_git_environment(
        &home,
        &config_home,
        &dangling_worktree,
        &selected_repository,
        &["set", "codex.hooks.autoStartIndexer", "false"],
    );

    for (operation, output) in [("list", listed), ("set", disabled)] {
        assert!(
            !output.status.success(),
            "{operation} unexpectedly accepted poisoned Git identity: {output:?}",
        );
        let error: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("Git identity error JSON");
        assert_eq!(error["code"], "GIT_WORKTREE_METADATA_UNRESOLVABLE");
    }
    assert_eq!(
        std::fs::read_to_string(config_path).expect("unchanged consent file"),
        enabled_contents,
    );
}

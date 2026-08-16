    #[test]
    fn shared_git_fixtures_map_to_exact_deterministic_global_paths() {
        let workspaces_root = PathBuf::from("/global/workspaces");
        for fixture in include_str!(
            "../../../../../../analysis-api/src/test/resources/workspace-git-layout-fixtures.tsv"
        )
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        {
            let fields = fixture.split('\t').collect::<Vec<_>>();
            assert_eq!(fields.len(), 4, "invalid fixture: {fixture}");
            let workspace = GitWorkspace {
                toplevel: PathBuf::from(fields[0]),
                common_dir: PathBuf::from(fields[1]),
                git_dir: PathBuf::from(fields[2]),
            };

            assert_eq!(
                workspace_data_directory_for_git(&workspaces_root, &workspace)
                    .expect("workspace"),
                workspaces_root.join(fields[3]),
            );
        }
    }

    #[test]
    fn git_workspace_data_directory_uses_stable_common_directory_path() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: PathBuf::from("/work/kast/.git"),
            git_dir: PathBuf::from("/work/kast/.git"),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace).expect("workspace"),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/kast--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let common_dir = PathBuf::from("/work/kast/.git");
        let first = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: common_dir.clone(),
            git_dir: common_dir.clone(),
        };
        let second = GitWorkspace {
            toplevel: PathBuf::from("/work/kast-feature"),
            common_dir,
            git_dir: PathBuf::from("/work/kast/.git/worktrees/kast-feature"),
        };

        assert_ne!(
            workspace_data_directory_for_git(&workspaces_root, &first).expect("first"),
            workspace_data_directory_for_git(&workspaces_root, &second).expect("second"),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace).expect("workspace"),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/private--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn changing_or_removing_origin_does_not_change_git_workspace_directory() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let toplevel = temp.path().join("kast");
        fs::create_dir_all(&toplevel).expect("workspace");
        let git = |args: &[&str]| {
            let status = std::process::Command::new("git")
                .args(args)
                .current_dir(&toplevel)
                .status()
                .expect("git command");
            assert!(status.success(), "git command failed: {args:?}");
        };
        git(&["init", "--quiet"]);
        git(&["remote", "add", "origin", "https://github.com/amichne/kast.git"]);
        let initial =
            workspace_data_directory_from(&workspaces_root, &toplevel).expect("initial");

        git(&[
            "remote",
            "set-url",
            "origin",
            "https://git.example.com/fork/renamed.git",
        ]);
        assert_eq!(
            initial,
            workspace_data_directory_from(&workspaces_root, &toplevel)
                .expect("changed origin"),
        );

        git(&["remote", "remove", "origin"]);
        assert_eq!(
            initial,
            workspace_data_directory_from(&workspaces_root, &toplevel)
                .expect("without origin"),
        );
    }

    #[test]
    fn present_unresolvable_git_metadata_cannot_fall_back_to_local_workspace_state() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let workspace_root = temp.path().join("linked-worktree");
        fs::create_dir(&workspace_root).expect("workspace");
        fs::write(
            workspace_root.join(".git"),
            "gitdir: ../common/.git/worktrees/missing\n",
        )
        .expect("dangling linked-worktree metadata");

        let error = workspace_data_directory_from(&workspaces_root, &workspace_root)
            .expect_err("present Git metadata must fail closed");

        assert_eq!(error.code, "GIT_WORKTREE_METADATA_UNRESOLVABLE");
        assert!(!workspaces_root.join("local").exists());
    }

    #[test]
    fn isolated_git_command_ignores_a_poisoned_repository_selector() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let poison = temp.path().join("poison");
        fs::create_dir(&workspace).expect("workspace");
        fs::create_dir(&poison).expect("poison");
        for root in [&workspace, &poison] {
            let status = std::process::Command::new("git")
                .args(["init", "--quiet"])
                .current_dir(root)
                .status()
                .expect("git init");
            assert!(status.success());
        }
        let mut command = std::process::Command::new("git");
        command
            .current_dir(&workspace)
            .env("GIT_DIR", poison.join(".git"));

        remove_git_repository_environment(&mut command);
        let output = command
            .args(["rev-parse", "--show-toplevel"])
            .output()
            .expect("git identity");

        assert!(output.status.success());
        assert_eq!(
            String::from_utf8_lossy(&output.stdout).trim(),
            workspace.canonicalize().unwrap().display().to_string(),
        );
    }

    #[test]
    fn unconfigured_git_worktree_consent_is_read_only() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let workspace_root = temp.path().join("workspace");
        fs::create_dir(&workspace_root).expect("workspace");
        let status = std::process::Command::new("git")
            .args(["init", "--quiet"])
            .current_dir(&workspace_root)
            .status()
            .expect("git init");
        assert!(status.success());

        let consent = exact_worktree_auto_start_consent_from(&workspaces_root, &workspace_root)
            .expect("read consent");

        assert_eq!(consent, IndexerAutoStartConsent::Unconfigured);
        assert!(!workspaces_root.exists());
    }

    #[test]
    fn exact_worktree_consent_reads_only_its_workspace_document() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let workspace_root = temp.path().join("workspace");
        fs::create_dir(&workspace_root).expect("workspace");
        let status = std::process::Command::new("git")
            .args(["init", "--quiet"])
            .current_dir(&workspace_root)
            .status()
            .expect("git init");
        assert!(status.success());
        let workspace = git_workspace(&workspace_root).expect("Git workspace");
        let config_path =
            git_workspace_data_directory(&workspaces_root, &workspace).join("config.toml");
        fs::create_dir_all(config_path.parent().expect("config parent")).expect("config parent");

        for (raw, expected) in [
            ("true", IndexerAutoStartConsent::Enabled),
            ("false", IndexerAutoStartConsent::Disabled),
        ] {
            fs::write(
                &config_path,
                format!("[codex.hooks]\nautoStartIndexer = {raw}\n"),
            )
            .expect("workspace consent");

            assert_eq!(
                exact_worktree_auto_start_consent_from(&workspaces_root, &workspace_root)
                    .expect("read consent"),
                expected,
            );
        }
    }

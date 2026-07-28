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

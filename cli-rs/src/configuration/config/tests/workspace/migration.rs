    #[test]
    fn unique_legacy_remote_worktree_migrates_to_stable_repository() {
        let fixture = git_migration_fixture(1);
        let legacy = &fixture.legacy_workspaces[0];
        fs::create_dir_all(legacy).expect("legacy workspace");
        fs::write(legacy.join("config.toml"), "[indexing]\n").expect("legacy config");
        let legacy_repository = legacy
            .parent()
            .and_then(Path::parent)
            .expect("legacy repository");
        fs::create_dir_all(legacy_repository.join("snapshots/retained"))
            .expect("legacy snapshots");
        fs::create_dir_all(
            legacy_repository
                .join("snapshots/retained/worktrees")
                .join(
                    legacy
                        .file_name()
                        .expect("legacy workspace leaf"),
                ),
        )
        .expect("snapshot internals");

        let resolved = workspace_data_directory_for_git(
            &fixture.workspaces_root,
            &fixture.workspace,
        )
        .expect("migrated workspace");

        assert_eq!(resolved, fixture.stable_workspace);
        assert!(resolved.join("config.toml").is_file());
        assert!(!legacy.exists());
        assert!(legacy_repository.join("snapshots/retained").is_dir());
        assert_eq!(
            workspace_data_directory_for_git(&fixture.workspaces_root, &fixture.workspace)
                .expect("stable workspace"),
            resolved,
        );
    }

    #[test]
    fn stable_and_legacy_worktree_state_conflict_fails_closed() {
        let fixture = git_migration_fixture(1);
        fs::create_dir_all(&fixture.stable_workspace).expect("stable workspace");
        fs::create_dir_all(&fixture.legacy_workspaces[0]).expect("legacy workspace");

        let error = workspace_data_directory_for_git(
            &fixture.workspaces_root,
            &fixture.workspace,
        )
        .expect_err("conflicting state must fail");

        assert_eq!(error.code, "WORKSPACE_STATE_MIGRATION_CONFLICT");
        assert!(fixture.stable_workspace.is_dir());
        assert!(fixture.legacy_workspaces[0].is_dir());
    }

    #[test]
    fn stable_worktree_state_is_reused_when_no_legacy_state_exists() {
        let fixture = git_migration_fixture(1);
        fs::create_dir_all(&fixture.stable_workspace).expect("stable workspace");
        fs::write(
            fixture.stable_workspace.join("config.toml"),
            "[cache]\n",
        )
        .expect("stable config");

        assert_eq!(
            workspace_data_directory_for_git(&fixture.workspaces_root, &fixture.workspace)
                .expect("stable workspace"),
            fixture.stable_workspace,
        );
        assert!(fixture.stable_workspace.join("config.toml").is_file());
    }

    #[test]
    fn multiple_legacy_worktree_states_fail_closed() {
        let fixture = git_migration_fixture(2);
        for legacy in &fixture.legacy_workspaces {
            fs::create_dir_all(legacy).expect("legacy workspace");
        }

        let error = workspace_data_directory_for_git(
            &fixture.workspaces_root,
            &fixture.workspace,
        )
        .expect_err("ambiguous legacy state must fail");

        assert_eq!(error.code, "WORKSPACE_STATE_MIGRATION_AMBIGUOUS");
        assert!(!fixture.stable_workspace.exists());
        assert!(fixture.legacy_workspaces.iter().all(|path| path.is_dir()));
    }

    #[test]
    fn legacy_repository_traversal_depth_fails_closed() {
        let fixture = git_migration_fixture(1);
        let legacy_root = fixture.legacy_workspaces[0]
            .ancestors()
            .nth(4)
            .expect("legacy Git root");
        let deep_repository = (1..=MAX_LEGACY_REPOSITORY_DEPTH + 1).fold(
            legacy_root.to_path_buf(),
            |path, depth| path.join(format!("group-{depth}")),
        );
        fs::create_dir_all(
            deep_repository
                .join("worktrees")
                .join(
                    fixture
                        .stable_workspace
                        .file_name()
                        .expect("stable workspace leaf"),
                ),
        )
        .expect("deep legacy workspace");

        let error =
            workspace_data_directory_for_git(&fixture.workspaces_root, &fixture.workspace)
                .expect_err("deep legacy state must fail");

        assert_eq!(error.code, "WORKSPACE_STATE_MIGRATION_DEPTH_EXCEEDED");
        assert!(!fixture.stable_workspace.exists());
    }

    #[test]
    fn git_worktree_hash_matches_toplevel_and_git_dir_contract() {
        let toplevel = PathBuf::from("/work/kast");
        let git_dir = PathBuf::from("/work/kast/.git/worktrees/kast");

        assert_eq!(
            git_worktree_hash(&toplevel, &git_dir),
            sha256_prefix("/work/kast\n/work/kast/.git/worktrees/kast"),
        );
    }

    struct GitMigrationFixture {
        _temp: tempfile::TempDir,
        workspaces_root: PathBuf,
        workspace: GitWorkspace,
        stable_workspace: PathBuf,
        legacy_workspaces: Vec<PathBuf>,
    }

    fn git_migration_fixture(legacy_repository_count: usize) -> GitMigrationFixture {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let toplevel = temp.path().join("workspace");
        let common_dir = temp.path().join("main.git");
        let git_dir = common_dir.join("worktrees/workspace");
        let workspace = GitWorkspace {
            toplevel,
            common_dir: common_dir.clone(),
            git_dir,
        };
        let leaf = format!(
            "workspace--{}",
            git_worktree_hash(&workspace.toplevel, &workspace.git_dir),
        );
        let stable_workspace = workspaces_root
            .join("git/local")
            .join(git_common_dir_hash(&common_dir))
            .join("worktrees")
            .join(&leaf);
        let legacy_workspaces = (1..=legacy_repository_count)
            .map(|index| {
                workspaces_root
                    .join("git")
                    .join(format!("host-{index}"))
                    .join("owner/group/repo/worktrees")
                    .join(&leaf)
            })
            .collect();
        GitMigrationFixture {
            _temp: temp,
            workspaces_root,
            workspace,
            stable_workspace,
            legacy_workspaces,
        }
    }

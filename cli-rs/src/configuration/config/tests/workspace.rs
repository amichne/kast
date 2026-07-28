    fn report_entry<'a>(entries: &'a [PathResolutionEntry], key: &str) -> &'a PathResolutionEntry {
        entries
            .iter()
            .find(|entry| entry.key == key)
            .unwrap_or_else(|| panic!("missing entry {key}: {entries:#?}"))
    }

    #[test]
    fn workspace_hash_matches_sha256_prefix_contract() {
        let path = PathBuf::from("/tmp/kast-workspace");
        let digest = Sha256::digest(path.to_string_lossy().as_bytes());
        assert_eq!(workspace_hash(&path), hex::encode(digest)[0..12]);
    }

    #[test]
    fn temporary_local_workspace_data_stays_under_the_global_data_root() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("global-data/workspaces");
        let workspace_root = temp.path().join("workspace");

        let resolved =
            workspace_data_directory_from(&workspaces_root, &workspace_root).expect("workspace data");

        assert_eq!(
            resolved,
            workspaces_root.join(format!(
                "local/{}--{}",
                sanitized_path(&workspace_root),
                workspace_hash(&workspace_root),
            )),
        );
        assert!(!workspaces_root.join("local-workspaces.json").exists());
        assert_ne!(resolved, workspace_root.join(".gradle/kast"));
    }

    #[test]
    fn local_workspace_data_honors_an_existing_registry_mapping_without_rewriting_it() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("global-data/workspaces");
        let workspace_root = temp.path().join("workspace");
        fs::create_dir_all(&workspaces_root).expect("workspaces root");
        let registry_path = workspaces_root.join("local-workspaces.json");
        let original = serde_json::json!({
            normalize(workspace_root.clone()).display().to_string(): "existing-workspace-id",
        })
        .to_string();
        fs::write(&registry_path, &original).expect("registry");

        let resolved =
            workspace_data_directory_from(&workspaces_root, &workspace_root).expect("workspace data");

        assert!(resolved.ends_with(format!(
            "{}--existing-workspace-id",
            sanitized_path(&workspace_root),
        )));
        assert_eq!(fs::read_to_string(registry_path).expect("registry"), original);
    }

    #[test]
    fn shared_local_fixtures_map_to_exact_deterministic_global_paths() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("global-data/workspaces");

        for fixture in include_str!(
            "../../../../../analysis-api/src/test/resources/workspace-local-layout-fixtures.tsv"
        )
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        {
            let fields = fixture.split('\t').collect::<Vec<_>>();
            assert_eq!(fields.len(), 2, "invalid fixture: {fixture}");

            assert_eq!(
                workspace_data_directory_from(&workspaces_root, Path::new(fields[0]))
                    .expect("workspace data"),
                workspaces_root.join(fields[1]),
            );
        }
        assert!(!workspaces_root.join("local-workspaces.json").exists());
    }

    #[test]
    fn workspace_cache_directory_uses_explicit_workspace_id() {
        let cache_home = PathBuf::from("/home/agent/.cache/kast");
        let workspace_root = PathBuf::from("/workspace/kast");

        assert_eq!(
            workspace_cache_directory(&cache_home, &workspace_root, Some("org/repo main")),
            PathBuf::from("/home/agent/.cache/kast/workspaces/org-repo-main"),
        );
    }

    #[test]
    fn workspace_cache_directory_defaults_to_workspace_hash() {
        let cache_home = PathBuf::from("/home/agent/.cache/kast");
        let workspace_root = PathBuf::from("/workspace/kast");

        assert_eq!(
            workspace_cache_directory(&cache_home, &workspace_root, None),
            cache_home
                .join("workspaces")
                .join(workspace_hash(&workspace_root)),
        );
    }

    #[test]
    fn workspace_cache_environment_moves_runtime_state_out_of_install_root() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let cache_home = PathBuf::from("/home/agent/.cache/kast");
        let mut config = KastConfig::defaults();
        config.paths.install_root = PathBuf::from("/opt/kast/current");
        config.apply_workspace_cache_home(&cache_home, &workspace_root, Some("kast-main"));

        assert_eq!(config.paths.cache_dir, cache_home);
        let workspace_dir = PathBuf::from("/home/agent/.cache/kast/workspaces/kast-main");
        assert_eq!(config.paths.logs_dir, workspace_dir.join("logs"));
        assert_eq!(config.paths.descriptor_dir, workspace_dir);
        assert!(!config.paths.descriptor_dir.starts_with("/opt/kast"));
    }

    #[test]
    fn configured_socket_dir_uses_workspace_local_socket_name() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let mut config = KastConfig::defaults();
        config.paths.socket_dir = PathBuf::from("/home/agent/.cache/kast/workspaces/kast-main");

        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            PathBuf::from(format!(
                "/home/agent/.cache/kast/workspaces/kast-main/kast-{}.sock",
                workspace_hash(&workspace_root)
            )),
        );
    }

    #[test]
    fn long_configured_socket_dir_falls_back_to_short_temp_socket() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let mut config = KastConfig::defaults();
        config.paths.socket_dir = PathBuf::from("/very")
            .join("long".repeat(25))
            .join("workspaces")
            .join("kast-main");

        assert!(socket_path_too_long(
            &config
                .paths
                .socket_dir
                .join(format!("kast-{}.sock", workspace_hash(&workspace_root)))
        ));
        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            fallback_socket_path(&workspace_root),
        );
    }

    #[test]
    fn default_socket_dir_uses_manifest_runtime_hash() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let config = KastConfig::defaults();

        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            config
                .paths
                .socket_dir
                .join(format!("kast-{}.sock", workspace_hash(&workspace_root))),
        );
    }

    #[test]
    fn shared_git_fixtures_map_to_exact_deterministic_global_paths() {
        let workspaces_root = PathBuf::from("/global/workspaces");
        for fixture in include_str!(
            "../../../../../analysis-api/src/test/resources/workspace-git-layout-fixtures.tsv"
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

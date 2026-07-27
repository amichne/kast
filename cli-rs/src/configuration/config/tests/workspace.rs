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
    fn parses_github_remotes() {
        let ssh = parse_git_remote("git@github.com:amichne/kast.git").unwrap();
        assert_eq!(ssh.host, "github.com");
        assert_eq!(ssh.owner, "amichne");
        assert_eq!(ssh.repo, "kast");

        let https = parse_git_remote("https://github.com/amichne/kast.git").unwrap();
        assert_eq!(https.host, "github.com");
        assert_eq!(https.owner, "amichne");
        assert_eq!(https.repo, "kast");
    }

    #[test]
    fn git_workspace_data_directory_uses_remote_worktree_path() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: PathBuf::from("/work/kast/.git"),
            git_dir: PathBuf::from("/work/kast/.git"),
            remote: Some(GitRemote {
                host: "github.com".to_string(),
                owner: "amichne".to_string(),
                repo: "kast".to_string(),
            }),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace),
            workspaces_root.join(format!(
                "git/github.com/amichne/kast/worktrees/kast--{}",
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let common_dir = PathBuf::from("/work/kast/.git");
        let remote = GitRemote {
            host: "github.com".to_string(),
            owner: "amichne".to_string(),
            repo: "kast".to_string(),
        };
        let first = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: common_dir.clone(),
            git_dir: common_dir.clone(),
            remote: Some(remote.clone()),
        };
        let second = GitWorkspace {
            toplevel: PathBuf::from("/work/kast-feature"),
            common_dir,
            git_dir: PathBuf::from("/work/kast/.git/worktrees/kast-feature"),
            remote: Some(remote),
        };

        assert_ne!(
            workspace_data_directory_for_git(&workspaces_root, &first),
            workspace_data_directory_for_git(&workspaces_root, &second),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
            remote: None,
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/private--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
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

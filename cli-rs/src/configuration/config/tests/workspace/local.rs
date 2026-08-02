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
            "../../../../../../analysis-api/src/test/resources/workspace-local-layout-fixtures.tsv"
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
                "/home/agent/.cache/kast/workspaces/kast-main/kast-indexer-{}.sock",
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
                .join(format!("kast-indexer-{}.sock", workspace_hash(&workspace_root)))
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
                .join(format!("kast-indexer-{}.sock", workspace_hash(&workspace_root))),
        );
    }

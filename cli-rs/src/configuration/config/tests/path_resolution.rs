    #[test]
    fn path_resolution_entries_mark_default_derivations() {
        let temp = tempfile::tempdir().unwrap();
        let install_root = temp.path().join("portable-kast");
        let mut config = KastConfig::defaults();
        config.paths.install_root = install_root.clone();
        config.paths.bin_dir = temp.path().join("bin");
        config.paths.lib_dir = install_root.join("current/lib");
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        config.paths.runtime_dir = install_root.join("runtime");
        config.paths.descriptor_dir = install_root.join("runtime/daemons");
        config.paths.socket_dir = install_root.join("runtime");
        config.cli.binary_path = temp.path().join("bin/kast");
        config.indexer.runtime_libs_dir =
            Some(install_root.join("current/lib/backends/indexer/current/runtime-libs"));

        let entries = path_resolution_entries(
            &config,
            PathResolutionMode::Cli,
            PathResolutionEntryContext::from_states(false, false, false, false),
        );
        let entry = |key: &str| report_entry(&entries, key);

        assert_eq!(entry("paths.binDir").derived_from, None);
        assert_eq!(entry("paths.binDir").source, PathResolutionSource::Default);
        assert_eq!(entry("paths.cacheDir").derived_from, None);
        assert_eq!(entry("paths.logsDir").derived_from, None);
        assert_eq!(
            entry("paths.libDir").derived_from.as_deref(),
            Some("paths.installRoot")
        );
        assert_eq!(
            entry("paths.runtimeDir").derived_from.as_deref(),
            Some("paths.installRoot")
        );
        assert_eq!(
            entry("paths.descriptorDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("paths.socketDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("cli.binaryPath").derived_from.as_deref(),
            Some("paths.binDir")
        );
        assert_eq!(
            entry("indexer.runtimeLibsDir")
                .derived_from
                .as_deref(),
            Some("paths.libDir")
        );
        assert!(entry("cli.binaryPath").used_by_idea);
        assert!(!entry("indexer.runtimeLibsDir").used_by_idea);
    }

    #[test]
    fn path_resolution_entries_mark_manifest_owned_derivations() {
        let mut config = KastConfig::defaults();
        config.indexer.runtime_libs_dir = Some(PathBuf::from(
            "/opt/kast/current/lib/backends/indexer/current/runtime-libs",
        ));

        let entries = path_resolution_entries(
            &config,
            PathResolutionMode::Cli,
            PathResolutionEntryContext::from_states(true, true, true, false),
        );
        let entry = |key: &str| report_entry(&entries, key);

        assert_eq!(
            entry("paths.installRoot").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(entry("paths.binDir").source, PathResolutionSource::Manifest);
        assert_eq!(
            entry("paths.cacheDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(
            entry("paths.logsDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(
            entry("paths.runtimeDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(entry("paths.runtimeDir").derived_from, None);
        assert_eq!(
            entry("paths.descriptorDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("paths.socketDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("indexer.runtimeLibsDir").source,
            PathResolutionSource::Manifest
        );
    }

    #[test]
    fn path_resolution_source_prefers_manifest_then_env_then_default() {
        assert_eq!(
            source_for_manifest_or_env_state(true, true),
            PathResolutionSource::Manifest
        );
        assert_eq!(
            source_for_manifest_or_env_state(false, true),
            PathResolutionSource::Env
        );
        assert_eq!(
            source_for_manifest_or_env_state(false, false),
            PathResolutionSource::Default
        );
    }

    #[test]
    fn env_value_present_matches_non_empty_path_env_contract() {
        assert!(!env_value_present(None));
        assert!(!env_value_present(Some(std::ffi::OsString::new())));
        assert!(env_value_present(Some(std::ffi::OsString::from(
            "/tmp/kast"
        ))));
    }

    #[test]
    fn cli_dynamic_output_is_behavior_config_not_install_owned() {
        assert!(install_owned_config_key("cli.binaryPath"));
        assert!(!install_owned_config_key("cli.dynamicOutput"));
    }

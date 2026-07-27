    #[test]
    fn parses_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "auto"
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.runtime.default_backend, RuntimeDefaultBackend::Auto);
    }

    #[test]
    fn parses_runtime_strict_plugin_matching() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
strictPluginMatching = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        assert!(config.runtime.strict_plugin_matching);
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(!config.runtime.strict_plugin_matching);
    }

    #[test]
    fn codex_hooks_default_enabled_and_parse_independently() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[codex.hooks]
postToolUse = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(config.codex.hooks.enabled);
        assert!(config.codex.hooks.session_start);
        assert!(!config.codex.hooks.post_tool_use);
    }

    #[test]
    fn install_owned_paths_in_toml_are_ignored() {
        let temp = tempfile::tempdir().unwrap();
        let install_root = temp.path().join("portable-kast");
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            format!(
                r#"[paths]
installRoot = "{}"
"#,
                install_root.display()
            ),
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        let defaults = config.clone();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.paths.install_root, defaults.paths.install_root);
        assert_eq!(config.paths.bin_dir, defaults.paths.bin_dir);
        assert_eq!(config.paths.lib_dir, defaults.paths.lib_dir);
        assert_eq!(config.paths.cache_dir, defaults.paths.cache_dir);
        assert_eq!(config.paths.logs_dir, defaults.paths.logs_dir);
        assert_eq!(config.paths.runtime_dir, defaults.paths.runtime_dir);
        assert_eq!(config.paths.descriptor_dir, defaults.paths.descriptor_dir);
        assert_eq!(config.paths.socket_dir, defaults.paths.socket_dir);
        assert_eq!(config.cli.binary_path, defaults.cli.binary_path);
        assert_eq!(
            config.backends.headless.runtime_libs_dir,
            defaults.backends.headless.runtime_libs_dir
        );
    }

    #[test]
    fn install_owned_path_overrides_are_ignored() {
        let temp = tempfile::tempdir().unwrap();
        let first_root = temp.path().join("first-root");
        let second_root = temp.path().join("second-root");
        let explicit_bin = temp.path().join("tools/bin");
        let explicit_lib = temp.path().join("runtime/lib");
        let explicit_cache = temp.path().join("runtime/cache");
        let explicit_logs = temp.path().join("runtime/logs");
        let explicit_runtime = temp.path().join("runtime");
        let explicit_descriptor = temp.path().join("runtime/descriptors");
        let explicit_socket = temp.path().join("runtime/socket");
        let explicit_binary = temp.path().join("custom/kast");
        let explicit_runtime_libs = temp.path().join("custom/runtime-libs");
        let first_config = temp.path().join("first.toml");
        let second_config = temp.path().join("second.toml");
        fs::write(
            &first_config,
            format!(
                r#"[paths]
installRoot = "{}"
binDir = "{}"
libDir = "{}"
cacheDir = "{}"
logsDir = "{}"
runtimeDir = "{}"
descriptorDir = "{}"
socketDir = "{}"

[backends.headless]
runtimeLibsDir = "{}"

[cli]
binaryPath = "{}"
"#,
                first_root.display(),
                explicit_bin.display(),
                explicit_lib.display(),
                explicit_cache.display(),
                explicit_logs.display(),
                explicit_runtime.display(),
                explicit_descriptor.display(),
                explicit_socket.display(),
                explicit_runtime_libs.display(),
                explicit_binary.display()
            ),
        )
        .unwrap();
        fs::write(
            &second_config,
            format!(
                r#"[paths]
installRoot = "{}"
"#,
                second_root.display()
            ),
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        let defaults = config.clone();
        config.apply(read_partial_config(&first_config).unwrap());
        config.apply(read_partial_config(&second_config).unwrap());

        assert_eq!(config.paths.install_root, defaults.paths.install_root);
        assert_eq!(config.paths.bin_dir, defaults.paths.bin_dir);
        assert_eq!(config.paths.lib_dir, defaults.paths.lib_dir);
        assert_eq!(config.paths.cache_dir, defaults.paths.cache_dir);
        assert_eq!(config.paths.logs_dir, defaults.paths.logs_dir);
        assert_eq!(config.paths.runtime_dir, defaults.paths.runtime_dir);
        assert_eq!(config.paths.descriptor_dir, defaults.paths.descriptor_dir);
        assert_eq!(config.paths.socket_dir, defaults.paths.socket_dir);
        assert_eq!(config.cli.binary_path, defaults.cli.binary_path);
        assert_eq!(
            config.backends.headless.runtime_libs_dir,
            defaults.backends.headless.runtime_libs_dir
        );
    }

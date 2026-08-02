    #[test]
    fn parses_indexer_host_command() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[indexer]
hostCommand = "/Applications/IntelliJ IDEA.app"
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(
            config.indexer.host_command,
            PathBuf::from("/Applications/IntelliJ IDEA.app")
        );
    }

    #[test]
    fn runtime_loading_ignores_legacy_backend_configuration() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "idea"

[runtime.ideaLaunch]
command = "/legacy/idea"
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.indexer.host_command, PathBuf::from("idea"));
    }

    #[test]
    fn parses_cli_dynamic_output_policy() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[cli]
dynamicOutput = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(!config.cli.dynamic_output);
    }

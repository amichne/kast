    #[test]
    fn parses_runtime_idea_launch() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "idea"

[runtime.ideaLaunch]
enabled = true
command = "/usr/local/bin/idea"
waitTimeoutMillis = 45678
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.runtime.default_backend, RuntimeDefaultBackend::Idea);
        assert!(config.runtime.idea_launch.enabled);
        assert_eq!(
            config.runtime.idea_launch.command,
            PathBuf::from("/usr/local/bin/idea")
        );
        assert_eq!(config.runtime.idea_launch.wait_timeout_millis.get(), 45_678);
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

    #[test]
    fn project_open_defaults_to_enabled_guidance_setup_with_git_excludes() {
        let config = KastConfig::defaults();

        assert!(config.project_open.profile_auto_init);
        assert_eq!(
            config.project_open.profile,
            ProjectOpenProfile::JetbrainsPlugin
        );
        assert!(config.project_open.auto_exclude_git);
        assert!(config.project_open.gradle_load_enabled);
    }

    #[test]
    fn parses_project_open_auto_init_policy() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[projectOpen]
profileAutoInit = true
profile = "jetbrains-plugin"
autoExcludeGit = false
gradleLoadEnabled = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(config.project_open.profile_auto_init);
        assert_eq!(
            config.project_open.profile,
            ProjectOpenProfile::JetbrainsPlugin
        );
        assert!(!config.project_open.auto_exclude_git);
        assert!(!config.project_open.gradle_load_enabled);
    }

    #[test]
    fn rejects_invalid_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "sidecar"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("sidecar"), "{}", error.message);
        assert!(error.message.contains("headless"), "{}", error.message);
    }

    #[test]
    fn rejects_invalid_project_open_profile() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[projectOpen]
profile = "unknown"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("unknown"), "{}", error.message);
        assert!(
            error.message.contains("jetbrains-plugin"),
            "{}",
            error.message
        );
    }

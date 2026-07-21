#[cfg(test)]
mod tests {
    use super::*;

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
        config.backends.headless.runtime_libs_dir =
            Some(install_root.join("current/lib/backends/headless/current/runtime-libs"));

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
            entry("backends.headless.runtimeLibsDir")
                .derived_from
                .as_deref(),
            Some("paths.libDir")
        );
        assert!(entry("cli.binaryPath").used_by_idea);
        assert!(!entry("backends.headless.runtimeLibsDir").used_by_idea);
    }

    #[test]
    fn path_resolution_entries_mark_manifest_owned_derivations() {
        let mut config = KastConfig::defaults();
        config.backends.headless.runtime_libs_dir = Some(PathBuf::from(
            "/opt/kast/current/lib/backends/headless/current/runtime-libs",
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
            entry("backends.headless.runtimeLibsDir").source,
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
}

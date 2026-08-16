#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn java_command_uses_indexer_classpath_entries_relative_to_runtime_libs() {
        let temp = tempfile::tempdir().unwrap();
        let libs = temp.path().join("runtime-libs");
        fs::create_dir_all(&libs).unwrap();
        let mut file = fs::File::create(libs.join("classpath.txt")).unwrap();
        writeln!(file, "a.jar\nlib/b.jar").unwrap();
        let idea_home = temp.path().join("idea-home");
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).unwrap();
        let mut config = KastConfig::defaults();
        config.indexer.runtime_libs_dir = Some(libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(workspace),
            runtime_libs_dir: None,
            idea_home: None,
            socket_path: Some(temp.path().join("kast.sock")),
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            stdio: false,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        };
        let command = linux_indexer_java_command(&args, &config).unwrap();
        assert!(command.contains(&"-cp".to_string()));
        let cp = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert!(command[cp].contains(&libs.join("a.jar").display().to_string()));
        assert!(command[cp].contains(&libs.join("lib/b.jar").display().to_string()));
        assert!(command.contains(&INDEXER_MAIN_CLASS.to_string()));
        assert!(command.contains(&format!("--idea-home={}", idea_home.display())));
    }

    #[test]
    fn java_command_uses_indexer_runtime_libs_main_class_and_host_home() {
        let temp = tempfile::tempdir().unwrap();
        let indexer_libs = temp.path().join("indexer-runtime-libs");
        fs::create_dir_all(&indexer_libs).unwrap();
        fs::write(indexer_libs.join("classpath.txt"), "indexer.jar\n").unwrap();
        let idea_home = temp.path().join("idea-home");
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).unwrap();
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        config.paths.descriptor_dir = temp.path().join("descriptors");
        config.paths.socket_dir = temp.path().join("sockets");
        config.indexer.runtime_libs_dir = Some(indexer_libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(workspace),
            runtime_libs_dir: None,
            idea_home: None,
            socket_path: Some(temp.path().join("kast.sock")),
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            stdio: false,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        };

        let layout = IndexerProjectLayout::resolve(&args, &config).unwrap();
        let command = linux_indexer_java_command(&args, &config).unwrap();

        let cp = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert!(command[cp].contains(&indexer_libs.join("indexer.jar").display().to_string()));
        assert!(command.contains(&INDEXER_MAIN_CLASS.to_string()));
        assert!(command.contains(&format!("--idea-home={}", idea_home.display())));
        assert!(command.contains(&format!(
            "-Didea.config.path={}",
            layout.idea_config.display()
        )));
        assert!(command.contains(&format!(
            "-Didea.system.path={}",
            layout.idea_system.display()
        )));
        assert!(command.contains(&format!(
            "-Didea.log.path={}",
            layout.idea_log.display()
        )));
        assert!(command.contains(&format!(
            "-Didea.plugins.path={}",
            layout.plugins.display()
        )));
        assert!(command.contains(&"-Didea.force.use.core.classloader=true".to_string()));
        assert!(command.contains(&"--add-opens=java.base/java.lang=ALL-UNNAMED".to_string()));
    }

    #[test]
    fn managed_idea_paths_override_poisoned_ambient_java_options() {
        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).unwrap();
        let idea_home = temp.path().join("idea-home");
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        let layout = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap();
        let poison = "-Didea.config.path=/shared/config -Didea.system.path=/shared/system -Didea.log.path=/shared/log -Didea.plugins.path=/shared/plugins";
        let mut command = Vec::new();

        extend_indexer_jvm_args(&mut command, Some(poison), &idea_home, &layout);

        for (prefix, expected) in [
            ("-Didea.config.path=", &layout.idea_config),
            ("-Didea.system.path=", &layout.idea_system),
            ("-Didea.log.path=", &layout.idea_log),
            ("-Didea.plugins.path=", &layout.plugins),
        ] {
            assert_eq!(
                command.iter().rfind(|argument| argument.starts_with(prefix)),
                Some(&format!("{prefix}{}", expected.display())),
            );
        }
    }

    #[test]
    fn java_command_writes_resolved_runtime_config_json_for_indexer() {
        let temp = tempfile::tempdir().unwrap();
        let indexer_libs = temp.path().join("indexer-runtime-libs");
        fs::create_dir_all(&indexer_libs).unwrap();
        fs::write(indexer_libs.join("classpath.txt"), "indexer.jar\n").unwrap();
        let idea_home = temp.path().join("idea-home");
        let runtime_dir = temp.path().join("runtime");
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).unwrap();
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.runtime_dir = runtime_dir.clone();
        config.paths.descriptor_dir = runtime_dir.join("daemons");
        config.paths.socket_dir = runtime_dir.clone();
        config.indexer.runtime_libs_dir = Some(indexer_libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        config.server.max_results = 42;
        let args = DaemonStartArgs {
            workspace_root: Some(workspace),
            runtime_libs_dir: None,
            idea_home: None,
            socket_path: Some(temp.path().join("kast.sock")),
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            stdio: false,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        };

        let command = linux_indexer_java_command(&args, &config).unwrap();

        let config_arg = command
            .iter()
            .find_map(|arg| arg.strip_prefix("--runtime-config-file="))
            .expect("runtime config arg");
        let payload: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(config_arg).expect("runtime config json"))
                .expect("runtime config payload");
        assert_eq!(payload["server"]["maxResults"], 42);
        assert_eq!(
            payload["paths"]["runtimeDir"],
            runtime_dir.display().to_string()
        );
        assert_eq!(
            payload["paths"]["descriptorDir"],
            runtime_dir.join("daemons").display().to_string()
        );
        assert_eq!(
            payload["paths"]["socketDir"],
            runtime_dir.display().to_string()
        );
        assert_eq!(
            payload["indexer"]["runtimeLibsDir"],
            indexer_libs.display().to_string()
        );
        assert_eq!(
            payload["indexer"]["hostHome"],
            idea_home.display().to_string()
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_sidecar_uses_product_jbr_boot_classpath_and_isolated_paths() {
        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        let app = temp.path().join("IntelliJ IDEA.app");
        let contents = app.join("Contents");
        let resources = contents.join("Resources");
        let java = contents.join("jbr/Contents/Home/bin/java");
        let boot_jar = contents.join("lib/platform-loader.jar");
        std::fs::create_dir(&workspace).unwrap();
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::create_dir_all(java.parent().unwrap()).unwrap();
        std::fs::create_dir_all(boot_jar.parent().unwrap()).unwrap();
        std::fs::write(&java, "fixture").unwrap();
        std::fs::write(&boot_jar, "fixture").unwrap();
        std::fs::write(
            resources.join("product-info.json"),
            serde_json::to_vec(&serde_json::json!({
                "productCode": "IU",
                "dataDirectoryName": "IntelliJIdea2026.2",
                "launch": [{
                    "os": "macOS",
                    "arch": if cfg!(target_arch = "aarch64") { "aarch64" } else { "x86_64" },
                    "javaExecutablePath": "../jbr/Contents/Home/bin/java",
                    "bootClassPathJarNames": ["platform-loader.jar"],
                    "additionalJvmArguments": ["-Dfixture.product=true"],
                    "mainClass": "com.intellij.idea.Main"
                }]
            }))
            .unwrap(),
        )
        .unwrap();
        let mut config = KastConfig::defaults();
        config.paths.install_root = temp.path().join("install");
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        let payload = config
            .paths
            .install_root
            .join("current/lib/backends/indexer/current/idea-home/plugins/kast-indexer/lib");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(payload.join("kast-indexer.jar"), "fixture").unwrap();
        let args = DaemonStartArgs {
            workspace_root: Some(workspace.clone()),
            runtime_libs_dir: None,
            idea_home: None,
            socket_path: Some(temp.path().join("kast.sock")),
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            stdio: false,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        };

        let layout = IndexerProjectLayout::resolve(&args, &config).unwrap();
        let command = installed_idea_sidecar_java_command(&args, &config, &app).unwrap();

        assert_eq!(
            command.first(),
            Some(&std::fs::canonicalize(&java).unwrap().display().to_string()),
        );
        assert!(command.contains(&"-Dfixture.product=true".to_string()));
        let classpath = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert_eq!(
            command[classpath],
            std::fs::canonicalize(&boot_jar)
                .unwrap()
                .display()
                .to_string(),
        );
        assert!(command.contains(&"com.intellij.idea.Main".to_string()));
        assert!(command.contains(&"kast-indexer".to_string()));
        assert!(!command.contains(&"-Didea.force.use.core.classloader=true".to_string()));
        for path in [
            &layout.idea_config,
            &layout.idea_system,
            &layout.idea_log,
            &layout.plugins,
        ] {
            assert!(
                command
                    .iter()
                    .any(|arg| arg.contains(&path.display().to_string()))
            );
        }
        assert!(command.contains(&format!(
            "--indexer-storage-root={}",
            layout.identity.storage_root().display()
        )));
        assert_eq!(
            std::fs::canonicalize(layout.plugins.join("kast-indexer")).unwrap(),
            std::fs::canonicalize(payload.parent().unwrap()).unwrap(),
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn isolated_sidecar_plugin_link_retargets_an_upgraded_payload() {
        let temp = tempfile::tempdir().unwrap();
        let previous = temp.path().join("previous/kast-indexer");
        let current = temp.path().join("current/kast-indexer");
        let target = temp.path().join("sidecar/plugins/kast-indexer");
        std::fs::create_dir_all(&previous).unwrap();
        std::fs::create_dir_all(&current).unwrap();
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&previous, &target).unwrap();

        ensure_isolated_plugin_link(&current, &target).unwrap();

        assert_eq!(
            std::fs::canonicalize(target).unwrap(),
            std::fs::canonicalize(current).unwrap(),
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn isolated_sidecar_plugin_link_recovers_from_a_dangling_owned_link() {
        let temp = tempfile::tempdir().unwrap();
        let missing = temp.path().join("removed/kast-indexer");
        let current = temp.path().join("current/kast-indexer");
        let target = temp.path().join("sidecar/plugins/kast-indexer");
        std::fs::create_dir_all(&current).unwrap();
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(missing, &target).unwrap();

        ensure_isolated_plugin_link(&current, &target).unwrap();

        assert_eq!(
            std::fs::canonicalize(target).unwrap(),
            std::fs::canonicalize(current).unwrap(),
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn isolated_sidecar_plugin_link_preserves_a_non_symlink_path() {
        let temp = tempfile::tempdir().unwrap();
        let source = temp.path().join("current/kast-indexer");
        let target = temp.path().join("sidecar/plugins/kast-indexer");
        std::fs::create_dir_all(&source).unwrap();
        std::fs::create_dir_all(&target).unwrap();

        let error = ensure_isolated_plugin_link(&source, &target)
            .expect_err("a non-symlink sidecar path must not be replaced");

        assert_eq!(error.code, "DAEMON_START_ERROR");
        assert!(target.is_dir());
        assert!(!target.symlink_metadata().unwrap().file_type().is_symlink());
    }

    #[test]
    fn daemon_environment_pins_config_home_for_child_processes() {
        let environment = daemon_environment();

        assert_eq!(environment[0].0, "KAST_CONFIG_HOME");
        assert_eq!(environment[0].1, config::kast_config_home());
    }

    #[test]
    fn daemon_child_removes_repository_selecting_git_environment() {
        let mut command = Command::new("env");
        command.env("GIT_DIR", "/poisoned/repository");

        apply_daemon_environment(&mut command);

        assert!(command.get_envs().any(|(key, value)| {
            key == "GIT_DIR" && value.is_none()
        }));
    }

    include!("tests/process_owner.rs");
}

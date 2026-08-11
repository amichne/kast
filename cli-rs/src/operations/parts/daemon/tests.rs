#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    #[cfg(target_os = "macos")]
    pub(super) fn write_jar_fixture(path: &Path, entry_names: &[&str]) {
        let file = fs::File::create(path).expect("Kotlin JPS fixture");
        let mut archive = zip::ZipWriter::new(file);
        for entry_name in entry_names {
            archive
                .start_file(*entry_name, zip::write::SimpleFileOptions::default())
                .expect("jar fixture entry");
            archive.write_all(b"fixture").expect("jar fixture bytes");
        }
        archive.finish().expect("Kotlin JPS archive");
    }

    #[cfg(target_os = "macos")]
    pub(super) fn write_installed_kotlin_jps_fixture(idea_home: &Path) -> PathBuf {
        let kotlin_jps = idea_home.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
        fs::create_dir_all(kotlin_jps.parent().expect("Kotlin JPS directory"))
            .expect("Kotlin JPS directory");
        write_jar_fixture(
            &kotlin_jps,
            &["org/jetbrains/kotlin/jps/build/KotlinBuilder.class"],
        );
        let compiler_common =
            idea_home.join("plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar");
        write_jar_fixture(
            &compiler_common,
            &["org/jetbrains/kotlin/cli/common/arguments/Freezable.class"],
        );
        kotlin_jps
    }

    #[test]
    fn java_command_uses_indexer_classpath_entries_relative_to_runtime_libs() {
        let temp = tempfile::tempdir().unwrap();
        let libs = temp.path().join("runtime-libs");
        fs::create_dir_all(&libs).unwrap();
        let mut file = fs::File::create(libs.join("classpath.txt")).unwrap();
        writeln!(file, "a.jar\nlib/b.jar").unwrap();
        let idea_home = temp.path().join("idea-home");
        let mut config = KastConfig::defaults();
        config.indexer.runtime_libs_dir = Some(libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
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
            runtime_instance_id: None,
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
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        config.paths.descriptor_dir = temp.path().join("descriptors");
        config.paths.socket_dir = temp.path().join("sockets");
        config.indexer.runtime_libs_dir = Some(indexer_libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
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
            runtime_instance_id: None,
        };

        let command = linux_indexer_java_command(&args, &config).unwrap();

        let cp = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert!(command[cp].contains(&indexer_libs.join("indexer.jar").display().to_string()));
        assert!(command.contains(&INDEXER_MAIN_CLASS.to_string()));
        assert!(command.contains(&format!("--idea-home={}", idea_home.display())));
        assert!(command.contains(&format!(
            "-Didea.config.path={}",
            config.paths.cache_dir.join("idea-config").display()
        )));
        assert!(command.contains(&format!(
            "-Didea.system.path={}",
            config.paths.cache_dir.join("idea-system").display()
        )));
        assert!(command.contains(&format!(
            "-Didea.log.path={}",
            config.paths.logs_dir.join("idea").display()
        )));
        assert!(command.contains(&"-Didea.force.use.core.classloader=true".to_string()));
        assert!(
            !command
                .iter()
                .any(|arg| arg.starts_with("-Didea.plugins.path="))
        );
        assert!(command.contains(&"--add-opens=java.base/java.lang=ALL-UNNAMED".to_string()));
    }

    #[test]
    fn java_command_writes_resolved_runtime_config_json_for_indexer() {
        let temp = tempfile::tempdir().unwrap();
        let indexer_libs = temp.path().join("indexer-runtime-libs");
        fs::create_dir_all(&indexer_libs).unwrap();
        fs::write(indexer_libs.join("classpath.txt"), "indexer.jar\n").unwrap();
        let idea_home = temp.path().join("idea-home");
        let runtime_dir = temp.path().join("runtime");
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.runtime_dir = runtime_dir.clone();
        config.paths.descriptor_dir = runtime_dir.join("daemons");
        config.paths.socket_dir = runtime_dir.clone();
        config.indexer.runtime_libs_dir = Some(indexer_libs.clone());
        config.indexer.host_home = Some(idea_home.clone());
        config.server.max_results = 42;
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
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
            runtime_instance_id: None,
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
    fn installed_idea_preflight_rejects_platform_kotlin_class_in_renamed_payload_jar() {
        let temp = tempfile::tempdir().unwrap();
        let payload_plugin = temp.path().join("kast-indexer");
        let idea_home = temp.path().join("idea-home");
        write_installed_kotlin_jps_fixture(&idea_home);
        let renamed_payload_jar = payload_plugin.join("lib/renamed-support.jar");
        std::fs::create_dir_all(renamed_payload_jar.parent().unwrap()).unwrap();
        write_jar_fixture(
            &renamed_payload_jar,
            &["org/jetbrains/kotlin/cli/common/arguments/Freezable.class"],
        );

        let error = preflight_installed_idea_semantic_runtime(
            &payload_plugin,
            &idea_home,
            &temp.path().join("idea-system"),
        )
        .unwrap_err();

        assert_eq!(error.code, "INDEXER_DEPENDENCY_CONFLICT");
        assert!(error.message.contains("classloader"));
        assert!(error.message.contains("renamed-support.jar"));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_preflight_rejects_missing_kotlin_jps_dependency() {
        let temp = tempfile::tempdir().unwrap();
        let payload_plugin = temp.path().join("kast-indexer");
        std::fs::create_dir_all(payload_plugin.join("lib")).unwrap();
        let error = preflight_installed_idea_semantic_runtime(
            &payload_plugin,
            &temp.path().join("idea-home"),
            &temp.path().join("idea-system"),
        )
        .unwrap_err();
        assert_eq!(error.code, "INDEXER_DEPENDENCY_UNAVAILABLE");
        assert!(error.message.contains("kotlin-jps-plugin"));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_preflight_rejects_truncated_kotlin_jps_dependency() {
        let temp = tempfile::tempdir().unwrap();
        let payload_plugin = temp.path().join("kast-indexer");
        let idea_home = temp.path().join("idea-home");
        let kotlin_jps = idea_home.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
        std::fs::create_dir_all(payload_plugin.join("lib")).unwrap();
        std::fs::create_dir_all(kotlin_jps.parent().unwrap()).unwrap();
        std::fs::write(&kotlin_jps, b"PK\x03\x04fixture").unwrap();
        let error = preflight_installed_idea_semantic_runtime(
            &payload_plugin,
            &idea_home,
            &temp.path().join("idea-system"),
        )
        .unwrap_err();

        assert_eq!(error.code, "INDEXER_DEPENDENCY_INVALID");
        assert!(error.message.contains(&kotlin_jps.display().to_string()));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_preflight_rejects_missing_freezable_in_host_plugin() {
        let temp = tempfile::tempdir().unwrap();
        let payload_plugin = temp.path().join("kast-indexer");
        let idea_home = temp.path().join("idea-home");
        let kotlin_jps = idea_home.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
        let compiler_common =
            idea_home.join("plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar");
        std::fs::create_dir_all(kotlin_jps.parent().unwrap()).unwrap();
        std::fs::create_dir_all(payload_plugin.join("lib")).unwrap();
        write_jar_fixture(
            &kotlin_jps,
            &["org/jetbrains/kotlin/jps/build/KotlinBuilder.class"],
        );
        write_jar_fixture(&compiler_common, &["fixture/Unrelated.class"]);

        let error = preflight_installed_idea_semantic_runtime(
            &payload_plugin,
            &idea_home,
            &temp.path().join("idea-system"),
        )
        .unwrap_err();

        assert_eq!(error.code, "INDEXER_DEPENDENCY_INVALID");
        assert!(error.message.contains(&compiler_common.display().to_string()));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_preflight_rejects_corrupt_plugin_cache() {
        let temp = tempfile::tempdir().unwrap();
        let payload_plugin = temp.path().join("kast-indexer");
        let idea_home = temp.path().join("idea-home");
        let idea_system = temp.path().join("idea-system");
        let cache = idea_system.join("plugins/pluginsXMLIds.json");
        std::fs::create_dir_all(payload_plugin.join("lib")).unwrap();
        std::fs::create_dir_all(cache.parent().unwrap()).unwrap();
        write_installed_kotlin_jps_fixture(&idea_home);
        std::fs::write(&cache, "not-json").unwrap();

        let error = preflight_installed_idea_semantic_runtime(
            &payload_plugin,
            &idea_home,
            &idea_system,
        )
        .unwrap_err();

        assert_eq!(error.code, "INDEXER_CACHE_INVALID");
        assert!(error.message.contains(&cache.display().to_string()));
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
}

#[cfg(all(test, target_os = "macos"))]
#[path = "tests/installed_idea_launch.rs"]
mod installed_idea_launch_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn java_command_uses_headless_classpath_entries_relative_to_runtime_libs() {
        let temp = tempfile::tempdir().unwrap();
        let libs = temp.path().join("runtime-libs");
        fs::create_dir_all(&libs).unwrap();
        let mut file = fs::File::create(libs.join("classpath.txt")).unwrap();
        writeln!(file, "a.jar\nlib/b.jar").unwrap();
        let idea_home = temp.path().join("idea-home");
        let mut config = KastConfig::defaults();
        config.backends.headless.runtime_libs_dir = Some(libs.clone());
        config.backends.headless.idea_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
            backend_name: None,
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
        let command = java_command(&args, &config).unwrap();
        assert!(command.contains(&"-cp".to_string()));
        let cp = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert!(command[cp].contains(&libs.join("a.jar").display().to_string()));
        assert!(command[cp].contains(&libs.join("lib/b.jar").display().to_string()));
        assert!(command.contains(&HEADLESS_MAIN_CLASS.to_string()));
        assert!(command.contains(&format!("--idea-home={}", idea_home.display())));
    }

    #[test]
    fn java_command_uses_headless_runtime_libs_main_class_and_idea_home() {
        let temp = tempfile::tempdir().unwrap();
        let headless_libs = temp.path().join("headless-runtime-libs");
        fs::create_dir_all(&headless_libs).unwrap();
        fs::write(headless_libs.join("classpath.txt"), "headless.jar\n").unwrap();
        let idea_home = temp.path().join("idea-home");
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        config.paths.descriptor_dir = temp.path().join("descriptors");
        config.paths.socket_dir = temp.path().join("sockets");
        config.backends.headless.runtime_libs_dir = Some(headless_libs.clone());
        config.backends.headless.idea_home = Some(idea_home.clone());
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
            backend_name: Some(crate::cli::BackendName::Headless),
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

        let command = java_command(&args, &config).unwrap();

        let cp = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
        assert!(command[cp].contains(&headless_libs.join("headless.jar").display().to_string()));
        assert!(command.contains(&HEADLESS_MAIN_CLASS.to_string()));
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
    fn java_command_writes_resolved_runtime_config_json_for_headless() {
        let temp = tempfile::tempdir().unwrap();
        let headless_libs = temp.path().join("headless-runtime-libs");
        fs::create_dir_all(&headless_libs).unwrap();
        fs::write(headless_libs.join("classpath.txt"), "headless.jar\n").unwrap();
        let idea_home = temp.path().join("idea-home");
        let runtime_dir = temp.path().join("runtime");
        let mut config = KastConfig::defaults();
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.runtime_dir = runtime_dir.clone();
        config.paths.descriptor_dir = runtime_dir.join("daemons");
        config.paths.socket_dir = runtime_dir.clone();
        config.backends.headless.runtime_libs_dir = Some(headless_libs.clone());
        config.backends.headless.idea_home = Some(idea_home.clone());
        config.server.max_results = 42;
        config.project_open.profile_auto_init = false;
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
            backend_name: Some(crate::cli::BackendName::Headless),
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

        let command = java_command(&args, &config).unwrap();

        let config_arg = command
            .iter()
            .find_map(|arg| arg.strip_prefix("--runtime-config-file="))
            .expect("runtime config arg");
        let payload: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(config_arg).expect("runtime config json"))
                .expect("runtime config payload");
        assert_eq!(payload["server"]["maxResults"], 42);
        assert_eq!(payload["projectOpen"]["profileAutoInit"], false);
        assert!(payload.get("project_open").is_none());
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
            payload["backends"]["headless"]["runtimeLibsDir"],
            headless_libs.display().to_string()
        );
        assert_eq!(
            payload["backends"]["headless"]["ideaHome"],
            idea_home.display().to_string()
        );
    }

    #[test]
    fn java_command_rejects_idea_backend_launch() {
        let temp = tempfile::tempdir().unwrap();
        let libs = temp.path().join("runtime-libs");
        fs::create_dir_all(&libs).unwrap();
        fs::write(libs.join("classpath.txt"), "headless.jar\n").unwrap();
        let mut config = KastConfig::defaults();
        config.backends.headless.runtime_libs_dir = Some(libs);
        let args = DaemonStartArgs {
            workspace_root: Some(temp.path().to_path_buf()),
            backend_name: Some(crate::cli::BackendName::Idea),
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

        let error = java_command(&args, &config).unwrap_err();

        assert_eq!(error.code, "DAEMON_START_ERROR");
        assert!(error.message.contains("cannot be launched"));
    }

    #[test]
    fn daemon_environment_pins_config_home_for_child_processes() {
        let environment = daemon_environment();

        assert_eq!(environment[0].0, "KAST_CONFIG_HOME");
        assert_eq!(environment[0].1, config::kast_config_home());
    }
}

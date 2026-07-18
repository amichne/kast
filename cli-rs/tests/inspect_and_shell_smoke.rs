mod support;

use support::*;

#[test]
fn paths_report_distinguishes_global_defaults_from_workspace_cache_env() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let cache_home = temp.path().join("cache-home");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let global_paths = kast(&home, &config_home)
        .env("KAST_CACHE_HOME", &cache_home)
        .args(["--output", "json", "developer", "inspect", "paths"])
        .output()
        .expect("global paths");
    assert!(
        global_paths.status.success(),
        "global paths should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&global_paths.stdout),
        String::from_utf8_lossy(&global_paths.stderr)
    );
    let global: serde_json::Value =
        serde_json::from_slice(&global_paths.stdout).expect("global paths json");
    assert_eq!(
        path_report_entry(&global, "paths.cacheDir")["source"],
        "env"
    );
    assert_eq!(
        path_report_entry(&global, "paths.cacheDir")["value"],
        cache_home.display().to_string()
    );
    assert_eq!(
        path_report_entry(&global, "paths.logsDir")["source"],
        "default"
    );
    assert!(
        path_report_entry(&global, "paths.logsDir")
            .get("derivedFrom")
            .is_none()
    );
    assert_eq!(
        path_report_entry(&global, "paths.descriptorDir")["source"],
        "default"
    );
    assert_eq!(
        path_report_entry(&global, "paths.descriptorDir")["derivedFrom"],
        "paths.runtimeDir"
    );
    assert_eq!(
        path_report_entry(&global, "paths.socketDir")["source"],
        "default"
    );
    assert_eq!(
        path_report_entry(&global, "paths.socketDir")["derivedFrom"],
        "paths.runtimeDir"
    );

    let workspace_paths = kast(&home, &config_home)
        .env("KAST_CACHE_HOME", &cache_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "paths",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("workspace paths");
    assert!(
        workspace_paths.status.success(),
        "workspace paths should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&workspace_paths.stdout),
        String::from_utf8_lossy(&workspace_paths.stderr)
    );
    let workspace: serde_json::Value =
        serde_json::from_slice(&workspace_paths.stdout).expect("workspace paths json");
    for key in [
        "paths.cacheDir",
        "paths.logsDir",
        "paths.descriptorDir",
        "paths.socketDir",
    ] {
        assert_eq!(path_report_entry(&workspace, key)["source"], "env");
    }
    for key in ["paths.logsDir", "paths.descriptorDir", "paths.socketDir"] {
        assert_eq!(
            path_report_entry(&workspace, key)["derivedFrom"],
            "paths.cacheDir"
        );
    }
}

#[test]
fn top_level_help_exposes_production_and_developer_commands() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    let commands = if cfg!(target_os = "macos") {
        vec!["help", "version", "ready", "status", "developer"]
    } else {
        vec!["help", "version", "setup", "ready", "status", "developer"]
    };
    for command in commands {
        assert!(
            stdout
                .lines()
                .any(|line| line.trim_start().starts_with(command)),
            "top-level help should show {command}: {stdout}"
        );
    }
    let up_help = kast(&home, &config_home)
        .args(["developer", "runtime", "up", "--help"])
        .output()
        .expect("developer runtime up help");
    assert!(up_help.status.success());
    let up_help_stdout = String::from_utf8_lossy(&up_help.stdout);
    for visible in ["--workspace-root", "--backend"] {
        assert!(
            up_help_stdout.contains(visible),
            "up help should retain primary flag {visible}: {up_help_stdout}"
        );
    }

    let install_help = kast(&home, &config_home)
        .args(["developer", "release", "activate", "--help"])
        .output()
        .expect("developer release activate help");
    assert!(install_help.status.success());
    let install_help_stdout = String::from_utf8_lossy(&install_help.stdout);
    assert!(
        install_help_stdout.contains("bundle"),
        "release activate help should expose bundle activation: {install_help_stdout}"
    );

    let package_help = kast(&home, &config_home)
        .args(["developer", "release", "package", "--help"])
        .output()
        .expect("developer release package help");
    assert!(package_help.status.success());
    let package_help_stdout = String::from_utf8_lossy(&package_help.stdout);
    assert!(
        package_help_stdout.contains("ubuntu-debian-bundle"),
        "package help should expose Ubuntu/Debian bundle packaging: {package_help_stdout}"
    );

    let machine_help = kast(&home, &config_home)
        .args(["developer", "machine", "--help"])
        .output()
        .expect("developer machine help");
    assert!(machine_help.status.success());
    let machine_stdout = String::from_utf8_lossy(&machine_help.stdout);
    assert!(
        !machine_stdout.contains("doctor"),
        "machine help should not expose retired doctor vocabulary: {machine_stdout}"
    );

    let doctor_help = kast(&home, &config_home)
        .args(["developer", "machine", "doctor", "--help"])
        .output()
        .expect("doctor help");
    assert!(
        !doctor_help.status.success(),
        "machine doctor should be removed in favor of `kast ready --for machine` and `kast repair --for machine --apply`"
    );

    let ready_help = kast(&home, &config_home)
        .args(["ready", "--help"])
        .output()
        .expect("ready help");
    assert!(ready_help.status.success());
    let ready_stdout = String::from_utf8_lossy(&ready_help.stdout);
    assert!(
        !ready_stdout.contains("--fix") && ready_stdout.contains("--for <TARGET>"),
        "ready help should be read-only and expose target selection: {ready_stdout}"
    );
    let repair_help = kast(&home, &config_home)
        .args(["repair", "--help"])
        .output()
        .expect("repair help");
    assert!(repair_help.status.success());
    let repair_stdout = String::from_utf8_lossy(&repair_help.stdout);
    assert!(
        repair_stdout.contains("--apply") && repair_stdout.contains("--for <TARGET>"),
        "repair help should expose explicit repair apply gate: {repair_stdout}"
    );
}

#[test]
fn install_completion_command_renders_shell_completion_scripts() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let bash = kast(&home, &config_home)
        .args(["developer", "machine", "completion", "bash"])
        .output()
        .expect("bash completion");
    assert!(
        bash.status.success(),
        "bash completion should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&bash.stdout),
        String::from_utf8_lossy(&bash.stderr)
    );
    let bash_stdout = String::from_utf8_lossy(&bash.stdout);
    assert!(
        bash_stdout.contains("complete"),
        "bash completion should register a completion function: {bash_stdout}"
    );
    assert!(
        bash_stdout.contains("kast"),
        "bash completion should target the kast command: {bash_stdout}"
    );

    let zsh = kast(&home, &config_home)
        .args([
            "developer",
            "machine",
            "completion",
            "zsh",
            "--command-name",
            "custom-kast",
        ])
        .output()
        .expect("zsh completion");
    assert!(
        zsh.status.success(),
        "zsh completion should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&zsh.stdout),
        String::from_utf8_lossy(&zsh.stderr)
    );
    let zsh_stdout = String::from_utf8_lossy(&zsh.stdout);
    assert!(
        zsh_stdout.contains("#compdef custom-kast"),
        "zsh completion should use the requested command name: {zsh_stdout}"
    );
}

#[test]
fn install_shell_writes_path_and_completion_profile_integration() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let profile = temp.path().join(".zshrc");
    let empty_path = temp.path().join("empty-path");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config");
    std::fs::create_dir_all(&empty_path).expect("empty path");
    std::fs::write(
        config_home.join("config.toml"),
        "[paths]\nbinDir = \"/ignored\"\n",
    )
    .expect("config");
    let expected_bin_dir = default_bin_dir(&home);

    let install = kast(&home, &config_home)
        .env("PATH", &empty_path)
        .args([
            "--output",
            "json",
            "developer",
            "machine",
            "shell",
            "--shell",
            "zsh",
            "--profile",
            profile.to_str().expect("profile path"),
            "--command-name",
            "custom-kast",
        ])
        .output()
        .expect("machine shell");
    assert!(
        install.status.success(),
        "machine shell should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("machine shell json");
    assert_eq!(stdout["shell"], "zsh");
    assert_eq!(stdout["commandName"], "custom-kast");
    assert_eq!(stdout["binDir"], expected_bin_dir.display().to_string());
    assert_eq!(stdout["profileUpdated"], true);

    let source_file = PathBuf::from(stdout["sourceFile"].as_str().expect("source file"));
    let source = std::fs::read_to_string(&source_file).expect("source file content");
    assert!(
        source.contains(&format!(
            "export KAST_CONFIG_HOME={}",
            shell_single_quote(config_home.to_str().expect("config path"))
        )),
        "source file should export the active config home: {source}"
    );
    assert!(
        source.contains(&format!(
            "_kast_bin_dir={}",
            shell_single_quote(&expected_bin_dir.display().to_string())
        )),
        "source file should store the configured bin directory: {source}"
    );
    assert!(
        source.contains("export PATH=\"${_kast_bin_dir}:${PATH}\""),
        "source file should prepend the configured bin directory: {source}"
    );
    assert!(
        source.contains("custom-kast developer machine completion zsh --command-name custom-kast"),
        "source file should wire completions for custom-kast: {source}"
    );

    let profile_content = std::fs::read_to_string(&profile).expect("profile content");
    assert!(
        profile_content.contains("# >>> kast shell integration >>>"),
        "profile should contain a managed block: {profile_content}"
    );
    assert!(
        profile_content.contains(&format!(
            "source {}",
            shell_single_quote(source_file.to_str().expect("source file path"))
        )),
        "profile should source the managed integration file: {profile_content}"
    );
}

#[test]
fn developer_machine_defaults_configures_idea_plugin_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let defaults = kast(&home, &config_home)
        .args(["--output", "json", "developer", "machine", "defaults"])
        .output()
        .expect("developer machine defaults");

    assert!(
        defaults.status.success(),
        "developer defaults should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&defaults.stdout),
        String::from_utf8_lossy(&defaults.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&defaults.stdout).expect("developer defaults json");
    assert_eq!(stdout["defaultBackend"], "idea");
    assert_eq!(stdout["ideaLaunchEnabled"], true);
    assert_eq!(stdout["ideaLaunchCommand"], "idea");
    assert_eq!(stdout["applied"], true);

    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("defaultBackend = \"idea\""), "{config}");
    assert!(config.contains("[runtime.ideaLaunch]"), "{config}");
    assert!(config.contains("enabled = true"), "{config}");
    assert!(config.contains("command = \"idea\""), "{config}");

    let up = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--no-auto-start=true",
        ])
        .output()
        .expect("runtime up");
    assert!(
        !up.status.success(),
        "runtime up should fail without a live IDEA backend"
    );
    let combined = format!(
        "{}{}",
        String::from_utf8_lossy(&up.stdout),
        String::from_utf8_lossy(&up.stderr)
    );
    if cfg!(target_os = "macos") {
        assert!(
            combined.contains("MACOS_PLUGIN_WORKSPACE_REQUIRED"),
            "{combined}"
        );
    } else {
        assert!(combined.contains("NO_BACKEND_AVAILABLE"), "{combined}");
        assert!(
            !combined.contains("Linux headless tarball"),
            "developer defaults should not fall through to headless guidance: {combined}"
        );
    }
}

#[test]
fn install_shell_prefers_running_cli_directory_over_stale_config_bin_dir() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let stale_bin = home.join(".kast/bin");
    let profile = temp.path().join(".zshrc");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
binDir = "{}"
"#,
            stale_bin.display()
        ),
    )
    .expect("config");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "machine",
            "shell",
            "--shell",
            "zsh",
            "--profile",
            profile.to_str().expect("profile path"),
        ])
        .output()
        .expect("machine shell");

    assert!(
        install.status.success(),
        "machine shell should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("machine shell json");
    let running_bin = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    assert_eq!(stdout["commandName"], "kast");
    assert_eq!(stdout["binDir"], running_bin.display().to_string());
    let source_file = PathBuf::from(stdout["sourceFile"].as_str().expect("source file"));
    let source = std::fs::read_to_string(&source_file).expect("source file content");
    assert!(
        !source.contains(&stale_bin.display().to_string()),
        "source file should not point at stale config binDir: {source}"
    );
    assert!(
        source.contains(&running_bin.display().to_string()),
        "source file should point at the running kast binary directory: {source}"
    );
}

fn shell_single_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

#[test]
fn help_topic_dumps_selected_command_help() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["help", "developer", "machine", "shell"])
        .output()
        .expect("help topic");

    assert!(
        help.status.success(),
        "help topic should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&help.stdout),
        String::from_utf8_lossy(&help.stderr)
    );
    let stdout = String::from_utf8_lossy(&help.stdout);
    assert!(
        stdout.contains("Install shell PATH and completion integration"),
        "selected help should include the command description: {stdout}"
    );
    assert!(
        stdout.contains("--source-file"),
        "selected help should include the command flags: {stdout}"
    );
    assert!(
        !stdout.contains("Help topic:"),
        "topic help should not use the placeholder renderer: {stdout}"
    );
}

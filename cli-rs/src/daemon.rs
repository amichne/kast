use crate::cli::{BackendName, DaemonStartArgs};
use crate::config::{self, KastConfig};
use crate::error::{CliError, Result};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const HEADLESS_MAIN_CLASS: &str = "io.github.amichne.kast.headless.HeadlessMainKt";

pub fn run_foreground(args: DaemonStartArgs) -> Result<i32> {
    let workspace_root =
        config::normalize(args.workspace_root.clone().unwrap_or(env::current_dir()?));
    let config = KastConfig::load(&workspace_root)?;
    let command = java_command(&args, &config)?;
    let mut process = Command::new(&command[0]);
    apply_daemon_environment(&mut process);
    let status = process
        .args(&command[1..])
        .current_dir(workspace_root)
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()?;
    Ok(status.code().unwrap_or(1))
}

pub fn spawn_background(args: DaemonStartArgs, log_file: &Path) -> Result<()> {
    let workspace_root =
        config::normalize(args.workspace_root.clone().unwrap_or(env::current_dir()?));
    let config = KastConfig::load(&workspace_root)?;
    let backend_name = args.backend_name.unwrap_or(BackendName::Headless);
    let command = java_command(&args, &config)?;
    if let Some(parent) = log_file.parent() {
        fs::create_dir_all(parent)?;
    }
    let log = fs::File::create(log_file)?;
    let log_err = log.try_clone()?;
    let mut process = Command::new(&command[0]);
    apply_daemon_environment(&mut process);
    process
        .args(&command[1..])
        .current_dir(workspace_root)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(log_err))
        .spawn()
        .map(|_| ())
        .map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Failed to auto-start the {} backend: {error}",
                    backend_name.canonical()
                ),
            )
        })
}

pub fn java_command(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    let backend_name = args.backend_name.unwrap_or(BackendName::Headless);
    if backend_name == BackendName::Idea {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            "The idea backend is hosted by IDEA and cannot be launched by kast daemon start.",
        ));
    }
    let runtime_libs_dir =
        config::backend_runtime_libs_dir(config, backend_name, args.runtime_libs_dir.clone())?;
    let classpath = read_classpath(&runtime_libs_dir)?;
    let java_exec = env::var("JAVA_HOME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .map(|java_home| {
            PathBuf::from(java_home)
                .join("bin/java")
                .display()
                .to_string()
        })
        .unwrap_or_else(|| "java".to_string());

    let mut command = vec![java_exec];
    let idea_home = headless_idea_home(args, config)?;
    command.extend(headless_jvm_args(&idea_home, config));
    if let Ok(java_opts) = env::var("JAVA_OPTS") {
        command.extend(java_opts.split_whitespace().map(ToOwned::to_owned));
    }
    command.push("-cp".to_string());
    command.push(classpath);
    command.push(HEADLESS_MAIN_CLASS.to_string());
    command.extend(config::server_launch_args(args, config)?);
    command.push(format!("--idea-home={}", idea_home.display()));
    Ok(command)
}

fn apply_daemon_environment(command: &mut Command) {
    for (key, value) in daemon_environment() {
        command.env(key, value);
    }
}

fn daemon_environment() -> [(&'static str, PathBuf); 1] {
    [("KAST_CONFIG_HOME", config::kast_config_home())]
}

fn headless_idea_home(args: &DaemonStartArgs, config: &KastConfig) -> Result<PathBuf> {
    args.idea_home
        .clone()
        .map(config::normalize)
        .or_else(|| config.backends.headless.idea_home.clone())
        .ok_or_else(|| {
            CliError::new(
                "DAEMON_START_ERROR",
                "Cannot locate IDEA home for headless backend. Set backends.headless.ideaHome in `kast config init` output, or pass --idea-home.",
            )
        })
}

fn headless_jvm_args(idea_home: &Path, config: &KastConfig) -> Vec<String> {
    let jna_arch = match env::consts::ARCH {
        "aarch64" => "aarch64",
        _ => "amd64",
    };
    let mut args = vec![
        format!(
            "-Xbootclasspath/a:{}",
            idea_home.join("lib/nio-fs.jar").display()
        ),
        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader".to_string(),
        "-Didea.force.use.core.classloader=true".to_string(),
        "-Didea.vendor.name=JetBrains".to_string(),
        "-Didea.paths.selector=KastHeadless".to_string(),
        format!(
            "-Didea.config.path={}",
            config.paths.cache_dir.join("idea-config").display()
        ),
        format!(
            "-Didea.system.path={}",
            config.paths.cache_dir.join("idea-system").display()
        ),
        format!(
            "-Didea.log.path={}",
            config.paths.logs_dir.join("idea").display()
        ),
        format!(
            "-Djna.boot.library.path={}",
            idea_home.join(format!("lib/jna/{jna_arch}")).display()
        ),
        "-Djna.nosys=true".to_string(),
        "-Djna.noclasspath=true".to_string(),
        format!(
            "-Dpty4j.preferred.native.folder={}",
            idea_home.join("lib/pty4j").display()
        ),
        "-Dio.netty.allocator.type=pooled".to_string(),
        format!(
            "-Dintellij.platform.runtime.repository.path={}",
            idea_home.join("modules/module-descriptors.dat").display()
        ),
        "-Didea.platform.prefix=Idea".to_string(),
        "-Dsplash=false".to_string(),
        "-Daether.connector.resumeDownloads=false".to_string(),
        "-Dcompose.swing.render.on.graphics=true".to_string(),
        format!(
            "-Dkast.headless.paths.cacheDir={}",
            config.paths.cache_dir.display()
        ),
        format!(
            "-Dkast.headless.paths.logsDir={}",
            config.paths.logs_dir.display()
        ),
        format!(
            "-Dkast.headless.paths.descriptorDir={}",
            config.paths.descriptor_dir.display()
        ),
        format!(
            "-Dkast.headless.paths.socketDir={}",
            config.paths.socket_dir.display()
        ),
        "--add-exports=java.desktop/com.apple.laf=ALL-UNNAMED".to_string(),
    ];
    args.extend(
        [
            "java.base/java.io",
            "java.base/java.lang",
            "java.base/java.lang.ref",
            "java.base/java.lang.reflect",
            "java.base/java.net",
            "java.base/java.nio",
            "java.base/java.nio.charset",
            "java.base/java.text",
            "java.base/java.time",
            "java.base/java.util",
            "java.base/java.util.concurrent",
            "java.base/java.util.concurrent.atomic",
            "java.base/java.util.concurrent.locks",
            "java.base/jdk.internal.ref",
            "java.base/jdk.internal.vm",
            "java.base/sun.net.dns",
            "java.base/sun.nio",
            "java.base/sun.nio.ch",
            "java.base/sun.nio.fs",
            "java.base/sun.security.ssl",
            "java.base/sun.security.util",
            "java.desktop/com.sun.java.swing",
            "java.desktop/com.sun.java.swing.plaf.gtk",
            "java.desktop/java.awt",
            "java.desktop/java.awt.dnd.peer",
            "java.desktop/java.awt.event",
            "java.desktop/java.awt.font",
            "java.desktop/java.awt.image",
            "java.desktop/java.awt.peer",
            "java.desktop/javax.swing",
            "java.desktop/javax.swing.plaf.basic",
            "java.desktop/javax.swing.text",
            "java.desktop/javax.swing.text.html",
            "java.desktop/javax.swing.text.html.parser",
            "java.desktop/sun.awt",
            "java.desktop/sun.awt.X11",
            "java.desktop/sun.awt.datatransfer",
            "java.desktop/sun.awt.image",
            "java.desktop/sun.font",
            "java.desktop/sun.java2d",
            "java.desktop/sun.swing",
            "java.management/sun.management",
            "jdk.attach/sun.tools.attach",
            "jdk.compiler/com.sun.tools.javac.api",
            "jdk.internal.jvmstat/sun.jvmstat.monitor",
            "jdk.jdi/com.sun.tools.jdi",
        ]
        .into_iter()
        .map(|module| format!("--add-opens={module}=ALL-UNNAMED")),
    );
    args
}

fn read_classpath(runtime_libs_dir: &Path) -> Result<String> {
    let classpath_file = runtime_libs_dir.join("classpath.txt");
    if !classpath_file.is_file() {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Backend runtime-libs classpath not found at {}. Reinstall the backend, update backends.headless.runtimeLibsDir, or pass --runtime-libs-dir.",
                classpath_file.display()
            ),
        ));
    }
    let entries: Vec<String> = fs::read_to_string(&classpath_file)?
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(|entry| runtime_libs_dir.join(entry).display().to_string())
        .collect();
    if entries.is_empty() {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Backend classpath.txt is empty at {}.",
                classpath_file.display()
            ),
        ));
    }
    Ok(entries.join(if cfg!(windows) { ";" } else { ":" }))
}

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
            "-Dkast.headless.paths.descriptorDir={}",
            config.paths.descriptor_dir.display()
        )));
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

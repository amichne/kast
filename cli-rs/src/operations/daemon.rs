use crate::cli::DaemonStartArgs;
use crate::config::{self, KastConfig};
use crate::error::{CliError, Result};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

const INDEXER_MAIN_CLASS: &str = "io.github.amichne.kast.indexer.KastIndexerMainKt";
#[cfg(target_os = "macos")]
const INDEXER_STARTER_COMMAND: &str = "kast-indexer";

pub fn java_command(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    #[cfg(target_os = "macos")]
    {
        let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
        let app = crate::runtime::resolve_installed_idea_sidecar_app(&workspace_root, config)?;
        installed_idea_sidecar_java_command(args, config, &app)
    }

    #[cfg(not(target_os = "macos"))]
    {
        linux_indexer_java_command(args, config)
    }
}

pub fn service_java_command(
    args: &DaemonStartArgs,
    config: &KastConfig,
    durable_runtime_config_path: &Path,
) -> Result<(Vec<String>, Vec<u8>)> {
    let mut command = java_command(args, config)?;
    let argument = command
        .iter_mut()
        .find(|argument| argument.starts_with("--runtime-config-file="))
        .ok_or_else(|| {
            CliError::new(
                "RUNTIME_REGISTRATION_INVALID",
                "Indexer command does not identify its runtime configuration.",
            )
        })?;
    let source = PathBuf::from(argument.trim_start_matches("--runtime-config-file="));
    let runtime_config = fs::read(source)?;
    *argument = format!(
        "--runtime-config-file={}",
        durable_runtime_config_path.display()
    );
    Ok((command, runtime_config))
}

#[cfg_attr(target_os = "macos", allow(dead_code))]
fn linux_indexer_java_command(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    let runtime_libs_dir = config::indexer_runtime_libs_dir(config, args.runtime_libs_dir.clone())?;
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
    let idea_home = indexer_host_home(args, config)?;
    let runtime_config_file =
        write_runtime_config_file(args, config, Some(&runtime_libs_dir), &idea_home)?;
    command.extend(indexer_jvm_args(&idea_home, config));
    if let Ok(java_opts) = env::var("JAVA_OPTS") {
        command.extend(
            java_opts
                .split_whitespace()
                .filter(|argument| !is_indexer_heap_argument(argument))
                .map(ToOwned::to_owned),
        );
    }
    command.push(config.indexer.max_heap_megabytes.jvm_argument());
    command.push("-cp".to_string());
    command.push(classpath);
    command.push(INDEXER_MAIN_CLASS.to_string());
    command.extend(config::server_launch_args(args, config)?);
    command.push(format!("--idea-home={}", idea_home.display()));
    command.push(format!(
        "--runtime-config-file={}",
        runtime_config_file.display()
    ));
    Ok(command)
}

include!("parts/daemon/installed_idea_preflight.rs");
include!("parts/daemon/installed_idea.rs");

fn write_runtime_config_file(
    args: &DaemonStartArgs,
    config: &KastConfig,
    runtime_libs_dir: Option<&Path>,
    idea_home: &Path,
) -> Result<PathBuf> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let runtime_config_dir = config.paths.cache_dir.join("runtime-config");
    fs::create_dir_all(&runtime_config_dir)?;
    let runtime_config_file = runtime_config_dir.join(format!(
        "{}-{}.json",
        config::workspace_hash(&workspace_root),
        "indexer",
    ));
    let mut runtime_config = config.clone();
    runtime_config.indexer.runtime_libs_dir = runtime_libs_dir.map(Path::to_path_buf);
    runtime_config.indexer.host_home = Some(idea_home.to_path_buf());
    if let Some(value) = args.request_timeout_ms {
        runtime_config.server.request_timeout_millis = value;
    }
    if let Some(value) = args.max_results {
        runtime_config.server.max_results = value;
    }
    if let Some(value) = args.max_concurrent_requests {
        runtime_config.server.max_concurrent_requests = value;
    }
    if args.profile {
        runtime_config.profiling.enabled = true;
    }
    if let Some(value) = &args.profile_modes {
        runtime_config.profiling.modes = value.clone();
    }
    if let Some(value) = args.profile_duration {
        runtime_config.profiling.duration_seconds = value;
    }
    if let Some(value) = &args.profile_otlp_endpoint {
        runtime_config.profiling.otlp_endpoint = Some(value.clone());
    }
    fs::write(
        &runtime_config_file,
        serde_json::to_vec_pretty(&runtime_config)?,
    )?;
    Ok(runtime_config_file)
}

#[cfg(test)]
fn daemon_environment() -> [(&'static str, PathBuf); 1] {
    [("KAST_CONFIG_HOME", config::kast_config_home())]
}

fn indexer_host_home(args: &DaemonStartArgs, config: &KastConfig) -> Result<PathBuf> {
    args.idea_home
        .clone()
        .map(config::normalize)
        .or_else(|| config.indexer.host_home.clone())
        .ok_or_else(|| {
            CliError::new(
                "DAEMON_START_ERROR",
                "Cannot locate the platform home for the installed indexer. Rerun `kast setup --source <bundle>`, or pass --idea-home for this launch.",
            )
        })
}

fn indexer_jvm_args(idea_home: &Path, config: &KastConfig) -> Vec<String> {
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
        "-Didea.paths.selector=KastIndexer".to_string(),
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

fn is_indexer_heap_argument(argument: &str) -> bool {
    argument.starts_with("-Xmx")
}

fn read_classpath(runtime_libs_dir: &Path) -> Result<String> {
    let classpath_file = runtime_libs_dir.join("classpath.txt");
    if !classpath_file.is_file() {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Backend runtime-libs classpath not found at {}. Rerun `kast setup --source <bundle>`, or pass --runtime-libs-dir for this launch.",
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

include!("parts/daemon/tests.rs");

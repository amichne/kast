use crate::cli::{BackendName, DaemonStartArgs};
use crate::config::{self, KastConfig};
use crate::error::{CliError, Result};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

const HEADLESS_MAIN_CLASS: &str = "io.github.amichne.kast.headless.HeadlessMainKt";
#[cfg(target_os = "macos")]
const HEADLESS_STARTER_COMMAND: &str = "kast-headless";

pub fn spawn_background(args: DaemonStartArgs, log_file: &Path) -> Result<Child> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
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
            "The idea backend is hosted by IDEA and cannot be launched as a headless runtime.",
        ));
    }

    #[cfg(target_os = "macos")]
    {
        let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
        let app = crate::runtime::resolve_installed_idea_sidecar_app(&workspace_root, config)?;
        return installed_idea_sidecar_java_command(args, config, &app);
    }

    #[cfg(not(target_os = "macos"))]
    {
        linux_headless_java_command(args, config, backend_name)
    }
}

#[cfg_attr(target_os = "macos", allow(dead_code))]
fn linux_headless_java_command(
    args: &DaemonStartArgs,
    config: &KastConfig,
    backend_name: BackendName,
) -> Result<Vec<String>> {
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
    let runtime_config_file = write_runtime_config_file(
        backend_name,
        args,
        config,
        Some(&runtime_libs_dir),
        &idea_home,
    )?;
    command.extend(headless_jvm_args(&idea_home, config));
    if let Ok(java_opts) = env::var("JAVA_OPTS") {
        command.extend(java_opts.split_whitespace().map(ToOwned::to_owned));
    }
    command.push("-cp".to_string());
    command.push(classpath);
    command.push(HEADLESS_MAIN_CLASS.to_string());
    command.extend(config::server_launch_args(args, config)?);
    command.push(format!("--idea-home={}", idea_home.display()));
    command.push(format!(
        "--runtime-config-file={}",
        runtime_config_file.display()
    ));
    Ok(command)
}

#[cfg(target_os = "macos")]
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledIdeaProductInfo {
    launch: Vec<InstalledIdeaProductLaunch>,
}

#[cfg(target_os = "macos")]
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledIdeaProductLaunch {
    os: String,
    arch: String,
    java_executable_path: PathBuf,
    vm_options_file_path: Option<PathBuf>,
    boot_class_path_jar_names: Vec<PathBuf>,
    #[serde(default)]
    additional_jvm_arguments: Vec<String>,
    main_class: String,
}

#[cfg(target_os = "macos")]
fn installed_idea_sidecar_java_command(
    args: &DaemonStartArgs,
    config: &KastConfig,
    app: &Path,
) -> Result<Vec<String>> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let idea_home = app.join("Contents");
    let resources = idea_home.join("Resources");
    let product_info_path = resources.join("product-info.json");
    let product_info: InstalledIdeaProductInfo =
        serde_json::from_slice(&fs::read(&product_info_path).map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot read installed IDEA product metadata at {}: {error}",
                    product_info_path.display(),
                ),
            )
        })?)
        .map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Installed IDEA product metadata is invalid at {}: {error}",
                    product_info_path.display(),
                ),
            )
        })?;
    let launch = product_info
        .launch
        .into_iter()
        .find(|launch| launch.os == "macOS" && product_arch_matches_current(&launch.arch))
        .ok_or_else(|| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "{} does not declare a macOS launch for architecture {}.",
                    product_info_path.display(),
                    env::consts::ARCH,
                ),
            )
        })?;
    let java_exec = canonical_product_file(&resources, &launch.java_executable_path, "JBR")?;
    let boot_classpath = launch
        .boot_class_path_jar_names
        .iter()
        .map(|name| canonical_product_file(&idea_home.join("lib"), name, "boot classpath"))
        .collect::<Result<Vec<_>>>()?;
    if boot_classpath.is_empty() {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "{} does not declare an IDEA platform boot classpath.",
                product_info_path.display(),
            ),
        ));
    }
    let classpath = env::join_paths(&boot_classpath)
        .map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!("Cannot construct the installed IDEA boot classpath: {error}"),
            )
        })?
        .to_string_lossy()
        .into_owned();

    let sidecar_root = config
        .paths
        .cache_dir
        .join("idea-sidecars")
        .join(config::workspace_hash(&workspace_root));
    let idea_config = sidecar_root.join("idea-config");
    let idea_system = sidecar_root.join("idea-system");
    let idea_log = sidecar_root.join("idea-log");
    let plugins = sidecar_root.join("plugins");
    for directory in [&idea_config, &idea_system, &idea_log, &plugins] {
        fs::create_dir_all(directory).map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot create isolated sidecar directory {}: {error}",
                    directory.display(),
                ),
            )
        })?;
    }
    let payload_plugin = installed_sidecar_plugin(args, config)?;
    let isolated_plugin = plugins.join("kast-headless");
    ensure_isolated_plugin_link(&payload_plugin, &isolated_plugin)?;
    let runtime_config_file =
        write_runtime_config_file(BackendName::Headless, args, config, None, &idea_home)?;

    let mut command = vec![java_exec.display().to_string()];
    if let Some(vm_options) = launch.vm_options_file_path.as_deref() {
        let vm_options = canonical_product_file(&resources, vm_options, "VM options")?;
        command.extend(product_jvm_arguments(
            &fs::read_to_string(&vm_options).map_err(|error| {
                CliError::new(
                    "DAEMON_START_ERROR",
                    format!(
                        "Cannot read IDEA VM options at {}: {error}",
                        vm_options.display()
                    ),
                )
            })?,
            app,
            &idea_home,
        ));
    }
    command.extend(
        launch
            .additional_jvm_arguments
            .iter()
            .map(|argument| materialize_product_argument(argument, app, &idea_home))
            .filter(|argument| !is_sidecar_managed_jvm_argument(argument)),
    );
    if let Ok(java_opts) = env::var("JAVA_OPTS") {
        command.extend(java_opts.split_whitespace().map(ToOwned::to_owned));
    }
    command.extend([
        "-Djava.awt.headless=true".to_string(),
        "-Didea.is.internal=true".to_string(),
        "-Dkast.idea.autostart=false".to_string(),
        format!("-Didea.home.path={}", idea_home.display()),
        format!(
            "-Didea.paths.selector=KastHeadless-{}",
            config::workspace_hash(&workspace_root),
        ),
        format!("-Didea.config.path={}", idea_config.display()),
        format!("-Didea.system.path={}", idea_system.display()),
        format!("-Didea.log.path={}", idea_log.display()),
        format!("-Didea.plugins.path={}", plugins.display()),
        format!("-Dkast.sidecar.plugin.path={}", isolated_plugin.display()),
        "-Dsplash=false".to_string(),
    ]);
    command.push("-cp".to_string());
    command.push(classpath);
    command.push(launch.main_class);
    command.push(HEADLESS_STARTER_COMMAND.to_string());
    command.extend(config::server_launch_args(args, config)?);
    command.push(format!("--idea-home={}", idea_home.display()));
    command.push(format!(
        "--runtime-config-file={}",
        runtime_config_file.display(),
    ));
    Ok(command)
}

#[cfg(target_os = "macos")]
fn product_arch_matches_current(product_arch: &str) -> bool {
    match env::consts::ARCH {
        "aarch64" => matches!(product_arch, "aarch64" | "arm64"),
        "x86_64" => matches!(product_arch, "x86_64" | "amd64"),
        architecture => product_arch == architecture,
    }
}

#[cfg(target_os = "macos")]
fn canonical_product_file(base: &Path, path: &Path, purpose: &str) -> Result<PathBuf> {
    fs::canonicalize(base.join(path)).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Installed IDEA {purpose} file is unavailable at {}: {error}",
                base.join(path).display(),
            ),
        )
    })
}

#[cfg(target_os = "macos")]
fn product_jvm_arguments(raw: &str, app: &Path, idea_home: &Path) -> Vec<String> {
    raw.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|argument| materialize_product_argument(argument, app, idea_home))
        .filter(|argument| !is_sidecar_managed_jvm_argument(argument))
        .collect()
}

#[cfg(target_os = "macos")]
fn materialize_product_argument(argument: &str, app: &Path, idea_home: &Path) -> String {
    argument
        .replace("$APP_PACKAGE", &app.display().to_string())
        .replace("$IDE_HOME", &idea_home.display().to_string())
        .replace("%IDE_HOME%", &idea_home.display().to_string())
}

#[cfg(target_os = "macos")]
fn is_sidecar_managed_jvm_argument(argument: &str) -> bool {
    [
        "-Djava.awt.headless=",
        "-Didea.home.path=",
        "-Didea.paths.selector=",
        "-Didea.config.path=",
        "-Didea.system.path=",
        "-Didea.log.path=",
        "-Didea.plugins.path=",
        "-Dkast.sidecar.plugin.path=",
        "-Dsplash=",
    ]
    .iter()
    .any(|prefix| argument.starts_with(prefix))
}

#[cfg(target_os = "macos")]
fn installed_sidecar_plugin(args: &DaemonStartArgs, config: &KastConfig) -> Result<PathBuf> {
    let conventional_home = config
        .paths
        .install_root
        .join("current/lib/backends/headless/current/idea-home");
    let mut homes = Vec::new();
    if let Some(home) = &args.idea_home {
        homes.push(home.clone());
    }
    if let Some(home) = &config.backends.headless.idea_home {
        homes.push(home.clone());
    }
    homes.push(conventional_home.clone());
    for home in homes {
        let plugin = home.join("plugins/kast-headless");
        if plugin.join("lib").is_dir()
            && fs::read_dir(plugin.join("lib")).is_ok_and(|entries| {
                entries
                    .filter_map(std::result::Result::ok)
                    .any(|entry| entry.path().extension().is_some_and(|value| value == "jar"))
            })
        {
            return Ok(plugin);
        }
    }
    Err(CliError::new(
        "DAEMON_START_ERROR",
        format!(
            "The Kast headless plugin payload is unavailable under {}. Run `kast setup` for the active release.",
            conventional_home.display(),
        ),
    ))
}

#[cfg(target_os = "macos")]
fn ensure_isolated_plugin_link(source: &Path, target: &Path) -> Result<()> {
    let source = fs::canonicalize(source).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot resolve sidecar plugin {}: {error}",
                source.display()
            ),
        )
    })?;
    let target_metadata = match target.symlink_metadata() {
        Ok(metadata) => Some(metadata),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => {
            return Err(CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot inspect isolated sidecar plugin path {}: {error}",
                    target.display(),
                ),
            ));
        }
    };
    if target_metadata
        .as_ref()
        .is_some_and(|metadata| !metadata.file_type().is_symlink())
    {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "The isolated sidecar plugin path {} is not a Kast-owned symlink.",
                target.display(),
            ),
        ));
    }
    if target_metadata.is_some()
        && fs::canonicalize(target).is_ok_and(|existing| existing == source)
    {
        return Ok(());
    }

    let temporary = target.with_file_name(format!(".kast-headless-{}.tmp", uuid::Uuid::new_v4(),));
    std::os::unix::fs::symlink(&source, &temporary).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot stage sidecar plugin link {} to {}: {error}",
                temporary.display(),
                source.display(),
            ),
        )
    })?;
    if let Err(error) = fs::rename(&temporary, target) {
        let _ = fs::remove_file(&temporary);
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot activate sidecar plugin link {} to {}: {error}",
                target.display(),
                source.display(),
            ),
        ));
    }
    Ok(())
}

fn write_runtime_config_file(
    backend_name: BackendName,
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
        backend_name.canonical(),
    ));
    let mut runtime_config = config.clone();
    runtime_config.backends.headless.runtime_libs_dir = runtime_libs_dir.map(Path::to_path_buf);
    runtime_config.backends.headless.idea_home = Some(idea_home.to_path_buf());
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
                "Cannot locate IDEA home for the manifest-backed headless backend. Rerun `kast setup --source <bundle>`, or pass --idea-home for this launch.",
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

use crate::SCHEMA_VERSION;
use crate::bundle::{
    BUNDLE_MANIFEST_FILE, BUNDLE_MANIFEST_KIND, BUNDLE_MANIFEST_SCHEMA_VERSION, BundleManifest,
    BundleVersion, HEADLESS_BACKEND_KIND, HEADLESS_BACKEND_NAME,
    UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID,
};
use crate::cli;
use crate::cli::{
    ActivateBundleArgs, CopilotInstallArgs, IdeaPluginInstallArgs, InstallArgs, InstallCommand,
    InstallRepairArgs, ResourceInstallArgs, ShellInstallArgs, ShellKind,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::manifest;
use crate::self_mgmt;
use flate2::read::GzDecoder;
use include_dir::{Dir, DirEntry, include_dir};
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::{Component, Path, PathBuf};
use std::process::{Command as ProcessCommand, Output, Stdio};
use std::thread;
use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};

static KAST_SKILL: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/resources/kast-skill");
static KAST_INSTRUCTIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/resources/kast-instructions");
static COPILOT_PLUGIN: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/resources/plugin");
const KAST_FORMULA_NAME: &str = "kast";
const KAST_PLUGIN_CASK_NAME: &str = "kast-plugin";
const DEFAULT_KAST_TAP: &str = "amichne/kast";
const COPILOT_PACKAGE_MARKER: &str = ".kast-copilot-version";
const COPILOT_PRIMITIVE_MANIFEST: &str = "primitive-manifest.json";
const SHELL_BLOCK_START: &str = "# >>> kast shell integration >>>";
const SHELL_BLOCK_END: &str = "# <<< kast shell integration <<<";
const COPILOT_GIT_EXCLUDE_BLOCK_START: &str = "# >>> kast copilot package >>>";
const COPILOT_GIT_EXCLUDE_BLOCK_END: &str = "# <<< kast copilot package <<<";

pub trait InstallReporter {
    fn idea_plugin_plan(&mut self, _plan: &IdeaPluginDownloadPlan) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_download_progress(&mut self, _downloaded_bytes: u64) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_download_finished(&mut self, _downloaded_bytes: u64) -> Result<()> {
        Ok(())
    }
}

pub struct NoopInstallReporter;

impl InstallReporter for NoopInstallReporter {}

pub struct HumanInstallReporter {
    last_downloaded_bytes: Option<u64>,
    download_frame: usize,
}

impl HumanInstallReporter {
    pub fn new() -> Self {
        Self {
            last_downloaded_bytes: None,
            download_frame: 0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct IdeaPluginDownloadPlan {
    pub cask_token: String,
    pub plugin_version: String,
    pub download_cache: PathBuf,
    pub plugin_directories: Vec<PathBuf>,
}

fn format_bytes(bytes: u64) -> String {
    const KIB: u64 = 1024;
    const MIB: u64 = KIB * 1024;
    if bytes >= MIB {
        format!("{:.1} MiB", bytes as f64 / MIB as f64)
    } else if bytes >= KIB {
        format!("{:.1} KiB", bytes as f64 / KIB as f64)
    } else {
        format!("{bytes} B")
    }
}

fn indeterminate_progress_bar(frame: usize) -> String {
    const WIDTH: usize = 16;
    const SEGMENT: usize = 5;
    let max_start = WIDTH.saturating_sub(SEGMENT);
    let start = frame % (max_start + 1);
    let mut bar = String::with_capacity(WIDTH + 2);
    bar.push('[');
    for index in 0..WIDTH {
        bar.push(if index >= start && index < start + SEGMENT {
            '#'
        } else {
            ' '
        });
    }
    bar.push(']');
    bar
}

impl InstallReporter for HumanInstallReporter {
    fn idea_plugin_plan(&mut self, plan: &IdeaPluginDownloadPlan) -> Result<()> {
        eprintln!();
        eprintln!("## Installing Kast IDEA plugin");
        eprintln!("- Cask token: `{}`", plan.cask_token);
        eprintln!("- Plugin version: `{}`", plan.plugin_version);
        eprintln!("- Download cache: `{}`", plan.download_cache.display());
        if !plan.plugin_directories.is_empty() {
            eprintln!("- JetBrains profile destinations:");
            for directory in &plan.plugin_directories {
                eprintln!("  - `{}`", directory.display());
            }
        }
        Ok(())
    }

    fn idea_plugin_download_progress(&mut self, downloaded_bytes: u64) -> Result<()> {
        self.last_downloaded_bytes = Some(downloaded_bytes);
        let bar = indeterminate_progress_bar(self.download_frame);
        self.download_frame = self.download_frame.wrapping_add(1);
        eprint!(
            "\r[download] {} pending Homebrew cask fetch: {}",
            bar,
            format_bytes(downloaded_bytes)
        );
        io::stderr().flush()?;
        Ok(())
    }

    fn idea_plugin_download_finished(&mut self, downloaded_bytes: u64) -> Result<()> {
        if self.last_downloaded_bytes.is_some() {
            eprintln!(
                "\r[download] [################] Homebrew cask fetch complete: {}",
                format_bytes(downloaded_bytes)
            );
        }
        self.last_downloaded_bytes = None;
        self.download_frame = 0;
        Ok(())
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallSkillResult {
    pub installed_at: String,
    pub version: String,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallInstructionsResult {
    pub installed_at: String,
    pub version: String,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallCopilotPackageResult {
    pub installed_at: String,
    pub version: String,
    pub skipped: bool,
    pub git_exclude: GitExcludeResult,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GitExcludeResult {
    pub attempted: bool,
    pub updated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exclude_file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallIdeaPluginResult {
    pub cask_token: String,
    pub plugin_version: String,
    pub download_cache: String,
    pub downloaded_bytes: u64,
    pub brew_action: String,
    pub brew_command: Vec<String>,
    pub brew_prefix: String,
    pub formula_prefix: String,
    pub cli_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jetbrains_config_root: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub plugin_directories: Vec<String>,
    pub dry_run: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallShellResult {
    pub shell: String,
    pub command_name: String,
    pub bin_dir: String,
    pub config_home: String,
    pub source_file: String,
    pub profile: String,
    pub profile_updated: bool,
    pub dry_run: bool,
    pub source_line: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallRepairAction {
    pub kind: String,
    pub target: String,
    pub status: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallRepairResult {
    pub applied: bool,
    pub config_path: String,
    pub apply_command: String,
    pub actions: Vec<InstallRepairAction>,
    pub backups: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum InstallResult {
    ActivateBundle(ActivateBundleResult),
    Skill(InstallSkillResult),
    Instructions(InstallInstructionsResult),
    Copilot(InstallCopilotPackageResult),
    IdeaPlugin(InstallIdeaPluginResult),
    Shell(InstallShellResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivateBundleResult {
    pub installed_at: String,
    pub version: String,
    pub platform: String,
    pub profile: String,
    pub install_root: String,
    pub current: String,
    pub manifest: String,
    pub active_binary: String,
    pub shim: String,
    pub skipped: bool,
    pub verify_only: bool,
    pub schema_version: u32,
}

#[derive(Debug)]
struct ValidatedBundle {
    root: PathBuf,
    manifest: BundleManifest,
    version: BundleVersion,
    cli_relative: PathBuf,
    backend_install_relative: PathBuf,
}

#[derive(Debug)]
struct ActivationTargetPaths {
    resolved: manifest::ResolvedKastPaths,
    version_dir: PathBuf,
    current_link: PathBuf,
    previous_link: PathBuf,
    headless_current_dir: PathBuf,
}

pub fn install(args: InstallArgs, reporter: &mut dyn InstallReporter) -> Result<InstallResult> {
    match args.command {
        InstallCommand::ActivateBundle(bundle_args) => {
            activate_bundle(bundle_args).map(InstallResult::ActivateBundle)
        }
        InstallCommand::Skill(resource_args) => {
            install_skill(resource_args).map(InstallResult::Skill)
        }
        InstallCommand::Instructions(resource_args) => {
            install_instructions(resource_args).map(InstallResult::Instructions)
        }
        InstallCommand::Copilot(resource_args) => {
            install_copilot(resource_args).map(InstallResult::Copilot)
        }
        InstallCommand::Plugin(resource_args) => {
            install_idea_plugin(resource_args, reporter).map(InstallResult::IdeaPlugin)
        }
        InstallCommand::Shell(shell_args) => install_shell(shell_args).map(InstallResult::Shell),
        InstallCommand::Completion(_) => Err(CliError::new(
            "CLI_USAGE",
            "`kast install completion` must be handled as raw completion output",
        )),
    }
}

pub fn activate_bundle(args: ActivateBundleArgs) -> Result<ActivateBundleResult> {
    let source = config::normalize(args.source.clone());
    let scratch = ScratchDir::new("kast-activate-bundle")?;
    let bundle_root = bundle_source_root(&source, scratch.path())?;
    let bundle = validate_bundle(&bundle_root)?;
    let targets = activation_target_paths(&args, &bundle)?;

    if args.verify_only {
        verify_activated_bundle(&bundle, &targets)?;
        return Ok(activate_bundle_result(&bundle, &targets, true, true));
    }

    if verify_activated_bundle(&bundle, &targets).is_ok() {
        return Ok(activate_bundle_result(&bundle, &targets, true, false));
    }

    install_validated_bundle(&bundle, &targets)?;
    verify_activated_bundle(&bundle, &targets)?;
    Ok(activate_bundle_result(&bundle, &targets, false, false))
}

fn bundle_source_root(source: &Path, scratch_root: &Path) -> Result<PathBuf> {
    if source.is_dir() {
        return Ok(source.to_path_buf());
    }
    if source.is_file() {
        return extract_bundle_tarball(source, &scratch_root.join("extract"));
    }
    Err(CliError::new(
        "BUNDLE_SOURCE_NOT_FOUND",
        format!("Bundle source was not found: {}", source.display()),
    ))
}

fn extract_bundle_tarball(archive_path: &Path, output_dir: &Path) -> Result<PathBuf> {
    let top_level = validate_tarball_members(archive_path)?;
    fs::create_dir_all(output_dir)?;
    let archive_file = fs::File::open(archive_path)?;
    let decoder = GzDecoder::new(archive_file);
    let mut archive = tar::Archive::new(decoder);
    archive.unpack(output_dir).map_err(|error| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Could not extract bundle archive {}: {error}",
                archive_path.display()
            ),
        )
    })?;
    let bundle_root = output_dir.join(top_level);
    if bundle_root.is_dir() {
        Ok(bundle_root)
    } else {
        Err(CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Bundle archive {} did not extract to a top-level directory.",
                archive_path.display()
            ),
        ))
    }
}

fn validate_tarball_members(archive_path: &Path) -> Result<PathBuf> {
    let archive_file = fs::File::open(archive_path)?;
    let decoder = GzDecoder::new(archive_file);
    let mut archive = tar::Archive::new(decoder);
    let mut top_level: Option<PathBuf> = None;
    let entries = archive.entries().map_err(|error| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Could not read bundle archive {}: {error}",
                archive_path.display()
            ),
        )
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| {
            CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                format!(
                    "Could not read bundle archive {}: {error}",
                    archive_path.display()
                ),
            )
        })?;
        if entry.header().entry_type().is_symlink() || entry.header().entry_type().is_hard_link() {
            return Err(CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                format!(
                    "Bundle archive {} must not contain link entries.",
                    archive_path.display()
                ),
            ));
        }
        let relative = safe_relative_path(&entry.path()?, "archive member")?;
        let Some(first_component) = relative.components().next() else {
            return Err(CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                "Bundle archive contains an empty member path.",
            ));
        };
        let current_top = PathBuf::from(first_component.as_os_str());
        match &top_level {
            Some(expected) if expected != &current_top => {
                return Err(CliError::new(
                    "BUNDLE_ARCHIVE_INVALID",
                    "Bundle archive must contain exactly one top-level directory.",
                ));
            }
            Some(_) => {}
            None => top_level = Some(current_top),
        }
    }
    top_level.ok_or_else(|| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!("Bundle archive is empty: {}", archive_path.display()),
        )
    })
}

fn validate_bundle(root: &Path) -> Result<ValidatedBundle> {
    let manifest = read_bundle_manifest(root)?;
    let version = validate_bundle_manifest_header(&manifest)?;
    validate_bundle_artifacts(&manifest)?;

    let cli_relative = bundle_manifest_path(&manifest.activation.cli.path, "activation.cli.path")?;
    let backend_install_relative = bundle_manifest_path(
        &manifest.activation.backend.install_dir,
        "activation.backend.installDir",
    )?;
    let launcher_relative = bundle_manifest_path(
        &manifest.activation.backend.launcher,
        "activation.backend.launcher",
    )?;
    let runtime_libs_relative = bundle_manifest_path(
        &manifest.activation.backend.runtime_libs_dir,
        "activation.backend.runtimeLibsDir",
    )?;
    let idea_home_relative = bundle_manifest_path(
        &manifest.activation.backend.idea_home,
        "activation.backend.ideaHome",
    )?;
    let required_plugin_relative = bundle_manifest_path(
        &manifest.activation.backend.required_plugin,
        "activation.backend.requiredPlugin",
    )?;

    validate_headless_activation(&manifest)?;

    let cli_path = root.join(&cli_relative);
    let backend_install_dir = root.join(&backend_install_relative);
    let backend_launcher = backend_install_dir.join(&launcher_relative);
    let runtime_libs_dir = backend_install_dir.join(&runtime_libs_relative);
    let idea_home = backend_install_dir.join(&idea_home_relative);
    let required_plugin = backend_install_dir.join(&required_plugin_relative);

    require_executable(&cli_path, "bundle CLI")?;
    require_directory(&backend_install_dir, "headless backend install directory")?;
    require_executable(&backend_launcher, "headless backend launcher")?;
    require_file(
        &runtime_libs_dir.join("classpath.txt"),
        "headless runtime classpath",
    )?;
    require_file(
        &idea_home.join("lib/nio-fs.jar"),
        "headless IDEA nio-fs.jar",
    )?;
    require_file(
        &idea_home.join("modules/module-descriptors.dat"),
        "headless IDEA module descriptors",
    )?;
    require_directory(&required_plugin, "bundled kast-headless plugin")?;

    Ok(ValidatedBundle {
        root: root.to_path_buf(),
        manifest,
        version,
        cli_relative,
        backend_install_relative,
    })
}

fn read_bundle_manifest(root: &Path) -> Result<BundleManifest> {
    let path = root.join(BUNDLE_MANIFEST_FILE);
    let content = fs::read_to_string(&path).map_err(|error| {
        CliError::new(
            "BUNDLE_MANIFEST_MISSING",
            format!("Could not read bundle manifest {}: {error}", path.display()),
        )
    })?;
    serde_json::from_str(&content).map_err(|error| {
        CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            format!("Invalid bundle manifest at {}: {error}", path.display()),
        )
    })
}

fn validate_bundle_manifest_header(manifest: &BundleManifest) -> Result<BundleVersion> {
    if manifest.schema_version != BUNDLE_MANIFEST_SCHEMA_VERSION {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_UNSUPPORTED",
            format!(
                "Unsupported bundle manifest schemaVersion {}; expected {}.",
                manifest.schema_version, BUNDLE_MANIFEST_SCHEMA_VERSION
            ),
        ));
    }
    if manifest.kind != BUNDLE_MANIFEST_KIND {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            format!(
                "Bundle manifest kind must be `{BUNDLE_MANIFEST_KIND}`, got `{}`.",
                manifest.kind
            ),
        ));
    }
    let version = BundleVersion::parse(&manifest.version).map_err(|message| {
        CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            format!("Bundle manifest version {message}."),
        )
    })?;
    if manifest.profile.trim().is_empty() {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Bundle manifest profile must not be empty.",
        ));
    }
    if manifest.platform != UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID {
        return Err(CliError::new(
            "BUNDLE_PLATFORM_UNSUPPORTED",
            format!(
                "Unsupported bundle platform `{}`; expected `{UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID}`.",
                manifest.platform
            ),
        ));
    }
    let _entrypoint = bundle_manifest_path(&manifest.entrypoint, "entrypoint")?;
    Ok(version)
}

fn validate_bundle_artifacts(manifest: &BundleManifest) -> Result<()> {
    let mut roles = BTreeSet::new();
    for artifact in &manifest.artifacts {
        if artifact.role.trim().is_empty() {
            return Err(CliError::new(
                "BUNDLE_MANIFEST_INVALID",
                "Bundle artifact role must not be empty.",
            ));
        }
        let _artifact_path = bundle_manifest_path(&artifact.path, "artifacts[].path")?;
        if artifact.source_sha256.trim().is_empty() {
            return Err(CliError::new(
                "BUNDLE_MANIFEST_INVALID",
                format!(
                    "Bundle artifact `{}` must record sourceSha256.",
                    artifact.role
                ),
            ));
        }
        roles.insert(artifact.role.as_str());
    }
    for role in ["cli", "headless-backend"] {
        if !roles.contains(role) {
            return Err(CliError::new(
                "BUNDLE_MANIFEST_INVALID",
                format!("Bundle manifest artifacts must include role `{role}`."),
            ));
        }
    }
    Ok(())
}

fn validate_headless_activation(manifest: &BundleManifest) -> Result<()> {
    let backend = &manifest.activation.backend;
    if backend.kind != HEADLESS_BACKEND_KIND || backend.name != HEADLESS_BACKEND_NAME {
        return Err(CliError::new(
            "BUNDLE_BACKEND_UNSUPPORTED",
            format!(
                "Unsupported bundle backend kind/name `{}/{}`; expected `{HEADLESS_BACKEND_KIND}/{HEADLESS_BACKEND_NAME}`.",
                backend.kind, backend.name
            ),
        ));
    }
    if backend.version.trim().is_empty() {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Bundle backend version must not be empty.",
        ));
    }
    let shim = &manifest.activation.shim;
    if !shim.exports_install_root || !shim.exports_config_home {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Headless bundle shim must export KAST_INSTALL_ROOT and KAST_CONFIG_HOME.",
        ));
    }
    if !shim
        .java_opts
        .iter()
        .any(|option| option == "-Didea.force.use.core.classloader=true")
    {
        return Err(CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Headless bundle shim must include -Didea.force.use.core.classloader=true.",
        ));
    }
    Ok(())
}

fn activation_target_paths(
    args: &ActivateBundleArgs,
    bundle: &ValidatedBundle,
) -> Result<ActivationTargetPaths> {
    let install_root = args
        .install_root
        .clone()
        .map(config::normalize)
        .or_else(|| env_path("KAST_INSTALL_ROOT"))
        .unwrap_or_else(|| manifest::home_dir().join(".local/share/kast"));
    let config_root = args
        .config_home
        .clone()
        .map(config::normalize)
        .or_else(|| env_path("KAST_CONFIG_HOME"))
        .unwrap_or_else(|| manifest::home_dir().join(".config/kast"));
    let bin_dir = args
        .bin_dir
        .clone()
        .map(config::normalize)
        .unwrap_or_else(|| manifest::home_dir().join(".local/bin"));
    let cache_dir =
        env_path("KAST_CACHE_HOME").unwrap_or_else(|| manifest::home_dir().join(".cache/kast"));
    let runtime_dir = install_root.join("runtime");
    let logs_dir = manifest::home_dir().join(".local/state/kast/logs");
    let locks_dir = install_root.join("locks");
    let version_dir = install_root.join("versions").join(bundle.version.as_str());
    let current_link = install_root.join("current");
    let previous_link = install_root.join("previous");
    let headless_current_dir = version_dir.join("lib/backends/headless/current");
    let lib_dir = current_link.join("lib");
    let resolved = manifest::ResolvedKastPaths {
        install_root: install_root.clone(),
        manifest_file: install_root.join(manifest::INSTALL_MANIFEST_FILE),
        bin_dir: bin_dir.clone(),
        lib_dir,
        data_dir: install_root.join("state"),
        cache_dir,
        logs_dir,
        runtime_dir: runtime_dir.clone(),
        locks_dir,
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: bin_dir.join("kast"),
        active_binary: version_dir.join(&bundle.cli_relative),
        headless_runtime_libs_dir: headless_current_dir.join("runtime-libs"),
        headless_idea_home: Some(headless_current_dir.join("idea-home")),
    };
    Ok(ActivationTargetPaths {
        resolved,
        version_dir,
        current_link,
        previous_link,
        headless_current_dir,
    })
}

fn install_validated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    if bundle.root.starts_with(&targets.resolved.install_root) {
        return Err(CliError::new(
            "BUNDLE_SOURCE_UNSAFE",
            format!(
                "Bundle source {} must not be inside the install root {}.",
                bundle.root.display(),
                targets.resolved.install_root.display()
            ),
        ));
    }
    let install_manifest = project_install_manifest(bundle, targets)?;
    manifest::with_install_lock(&targets.resolved, || {
        manifest::ensure_install_directories(&targets.resolved)?;
        let staged = targets.resolved.install_root.join("versions").join(format!(
            "{}.tmp-{}",
            bundle.version.as_str(),
            std::process::id()
        ));
        manifest::remove_path(&staged)?;
        copy_bundle_tree(&bundle.root, &staged)?;
        manifest::remove_path(&targets.version_dir)?;
        fs::rename(&staged, &targets.version_dir)?;
        link_active_headless_backend(bundle, targets)?;
        manifest::replace_symlink_or_copy(&targets.version_dir, &targets.current_link)?;
        if let Some(previous) = &install_manifest.previous_version {
            let previous_dir = targets
                .resolved
                .install_root
                .join("versions")
                .join(previous);
            if previous_dir.exists() {
                manifest::replace_symlink_or_copy(&previous_dir, &targets.previous_link)?;
            }
        }
        let active_binary = ensure_active_cli_path(bundle, targets)?;
        write_headless_kast_shim(
            &targets.resolved.shim_path,
            &active_binary,
            &targets.resolved.install_root,
            &targets.resolved.config_root,
            &bundle.manifest.activation.shim.java_opts,
        )?;
        write_headless_config(&targets.resolved.config_file)?;
        manifest::write_manifest_atomic(&targets.resolved.manifest_file, &install_manifest)?;
        Ok(())
    })
}

fn project_install_manifest(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<manifest::KastInstallManifest> {
    let previous = if targets.resolved.manifest_file.is_file() {
        let manifest = manifest_from_file(&targets.resolved.manifest_file)?;
        BundleVersion::parse(&manifest.active_version)
            .ok()
            .filter(|version| version.as_str() != bundle.version.as_str())
            .map(BundleVersion::into_string)
    } else {
        None
    };
    let now = manifest::current_timestamp();
    let normalized_version = bundle.version.normalized();
    let headless_root = targets.headless_current_dir.clone();
    let install_id = format!("kast-{}-{}", bundle.manifest.platform, normalized_version);
    Ok(manifest::KastInstallManifest {
        tool: "kast".to_string(),
        install_id,
        profile: bundle.manifest.profile.clone(),
        active_version: bundle.version.as_str().to_string(),
        previous_version: previous,
        created_at: now.clone(),
        updated_at: now,
        roots: manifest::ManifestRoots {
            install: targets.resolved.install_root.display().to_string(),
            bin: targets.resolved.bin_dir.display().to_string(),
            config: targets.resolved.config_root.display().to_string(),
            data: targets.resolved.data_dir.display().to_string(),
            cache: targets.resolved.cache_dir.display().to_string(),
            runtime: targets.resolved.runtime_dir.display().to_string(),
            logs: targets.resolved.logs_dir.display().to_string(),
            locks: targets.resolved.locks_dir.display().to_string(),
        },
        entrypoints: manifest::ManifestEntrypoints {
            shim: targets.resolved.shim_path.display().to_string(),
            active_binary: targets.resolved.active_binary.display().to_string(),
        },
        schemas: manifest::ManifestSchemas::default(),
        version: normalized_version.clone(),
        backend_version: bundle.manifest.activation.backend.version.clone(),
        installed_at: format!("{}:{}", bundle.manifest.platform, bundle.version.as_str()),
        platform: bundle.manifest.platform.clone(),
        components: vec![
            "cli".to_string(),
            "headless-backend".to_string(),
            "manifest".to_string(),
        ],
        backends: vec![manifest::BackendComponentState {
            name: "headless".to_string(),
            version: bundle.manifest.activation.backend.version.clone(),
            install_dir: headless_root.display().to_string(),
            runtime_libs_dir: headless_root.join("runtime-libs").display().to_string(),
            idea_home: Some(headless_root.join("idea-home").display().to_string()),
        }],
        managed_paths: vec![
            "bin".to_string(),
            "lib".to_string(),
            "cache".to_string(),
            "logs".to_string(),
        ],
        owned_paths: manifest::owned_paths(&targets.resolved),
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

fn verify_activated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    require_file(&targets.resolved.manifest_file, "install manifest")?;
    require_executable(&targets.resolved.shim_path, "kast shim")?;
    require_directory(&targets.version_dir, "installed bundle version")?;
    require_file(
        &targets
            .resolved
            .headless_runtime_libs_dir
            .join("classpath.txt"),
        "installed runtime classpath",
    )?;
    if let Some(idea_home) = &targets.resolved.headless_idea_home {
        require_file(
            &idea_home.join("lib/nio-fs.jar"),
            "installed IDEA nio-fs.jar",
        )?;
        require_file(
            &idea_home.join("modules/module-descriptors.dat"),
            "installed IDEA module descriptors",
        )?;
    }
    let manifest = manifest_from_file(&targets.resolved.manifest_file)?;
    if manifest.active_version != bundle.version.as_str() {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            format!(
                "Install manifest activeVersion is `{}`, expected `{}`.",
                manifest.active_version,
                bundle.version.as_str()
            ),
        ));
    }
    if manifest.entrypoints.active_binary != targets.resolved.active_binary.display().to_string() {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Install manifest activeBinary does not match the projected bundle activation path.",
        ));
    }
    let shim = fs::read_to_string(&targets.resolved.shim_path)?;
    for java_opt in &bundle.manifest.activation.shim.java_opts {
        if !shim.contains(java_opt) {
            return Err(CliError::new(
                "BUNDLE_INSTALL_MISMATCH",
                format!("Installed shim does not include required JVM option `{java_opt}`."),
            ));
        }
    }
    if !shim.contains("KAST_INSTALL_ROOT") || !shim.contains("KAST_CONFIG_HOME") {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Installed shim does not export KAST_INSTALL_ROOT and KAST_CONFIG_HOME.",
        ));
    }
    let output = ProcessCommand::new(&targets.resolved.shim_path)
        .arg("doctor")
        .env("KAST_INSTALL_ROOT", &targets.resolved.install_root)
        .env("KAST_CONFIG_HOME", &targets.resolved.config_root)
        .output()
        .map_err(|error| {
            CliError::new(
                "BUNDLE_DOCTOR_FAILED",
                format!("Could not run installed kast doctor: {error}"),
            )
        })?;
    if output.status.success() {
        Ok(())
    } else {
        Err(command_error(
            "BUNDLE_DOCTOR_FAILED",
            "Installed bundle did not pass kast doctor",
            &["doctor".to_string()],
            &output,
        ))
    }
}

fn manifest_from_file(path: &Path) -> Result<manifest::KastInstallManifest> {
    serde_json::from_str(&fs::read_to_string(path)?).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })
}

fn activate_bundle_result(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    skipped: bool,
    verify_only: bool,
) -> ActivateBundleResult {
    ActivateBundleResult {
        installed_at: targets.version_dir.display().to_string(),
        version: bundle.version.as_str().to_string(),
        platform: bundle.manifest.platform.clone(),
        profile: bundle.manifest.profile.clone(),
        install_root: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        manifest: targets.resolved.manifest_file.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        shim: targets.resolved.shim_path.display().to_string(),
        skipped,
        verify_only,
        schema_version: SCHEMA_VERSION,
    }
}

struct ScratchDir {
    path: PathBuf,
}

impl ScratchDir {
    fn new(label: &str) -> Result<Self> {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or_default();
        let path = env::temp_dir().join(format!("{label}-{}-{suffix}", std::process::id()));
        manifest::remove_path(&path)?;
        fs::create_dir_all(&path)?;
        Ok(Self { path })
    }

    fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for ScratchDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

fn safe_relative_path(path: &Path, field: &str) -> Result<PathBuf> {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Normal(value) => normalized.push(value),
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(CliError::new(
                    "BUNDLE_PATH_UNSAFE",
                    format!(
                        "Bundle {field} path `{}` must be relative and must not contain `..`.",
                        path.display()
                    ),
                ));
            }
        }
    }
    if normalized.as_os_str().is_empty() {
        return Err(CliError::new(
            "BUNDLE_PATH_UNSAFE",
            format!("Bundle {field} path must not be empty."),
        ));
    }
    Ok(normalized)
}

fn bundle_manifest_path(value: &str, field: &str) -> Result<PathBuf> {
    safe_relative_path(Path::new(value.trim()), field)
}

fn require_file(path: &Path, label: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_directory(path: &Path, label: &str) -> Result<()> {
    if path.is_dir() {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_executable(path: &Path, label: &str) -> Result<()> {
    require_file(path, label)?;
    if is_executable(path)? {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("{label} is not executable: {}", path.display()),
        ))
    }
}

#[cfg(unix)]
fn is_executable(path: &Path) -> Result<bool> {
    use std::os::unix::fs::PermissionsExt;
    Ok(fs::metadata(path)?.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_executable(path: &Path) -> Result<bool> {
    Ok(path.is_file())
}

fn copy_bundle_tree(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "BUNDLE_SOURCE_UNSAFE",
            format!(
                "Bundle source must not contain symlinks: {}",
                source.display()
            ),
        ));
    }
    if metadata.is_dir() {
        fs::create_dir_all(target)?;
        let mut entries = fs::read_dir(source)?.collect::<std::result::Result<Vec<_>, _>>()?;
        entries.sort_by_key(|entry| entry.path());
        for entry in entries {
            copy_bundle_tree(&entry.path(), &target.join(entry.file_name()))?;
        }
    } else if metadata.is_file() {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
        fs::set_permissions(target, metadata.permissions())?;
    }
    Ok(())
}

fn link_active_headless_backend(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let stable_backend_dir = targets.version_dir.join("lib/backends/headless");
    fs::create_dir_all(&stable_backend_dir)?;
    let current = stable_backend_dir.join("current");
    manifest::remove_path(&current)?;
    let backend_name = bundle.backend_install_relative.file_name().ok_or_else(|| {
        CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Bundle backend installDir must include a final path component.",
        )
    })?;
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(Path::new("..").join(backend_name), &current)?;
    }
    #[cfg(not(unix))]
    {
        let backend_dir = targets.version_dir.join(&bundle.backend_install_relative);
        copy_bundle_tree(&backend_dir, &current)?;
    }
    Ok(())
}

fn ensure_active_cli_path(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<PathBuf> {
    let active_binary = targets.version_dir.join(&bundle.cli_relative);
    if targets.resolved.shim_path == active_binary {
        let renamed = active_binary.with_file_name("kast-cli");
        manifest::remove_path(&renamed)?;
        fs::rename(&active_binary, &renamed)?;
        manifest::make_executable(&renamed)?;
        return Ok(renamed);
    }
    manifest::make_executable(&active_binary)?;
    Ok(active_binary)
}

fn write_headless_kast_shim(
    shim_path: &Path,
    active_binary: &Path,
    install_root: &Path,
    config_home: &Path,
    java_opts: &[String],
) -> Result<()> {
    if let Some(parent) = shim_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut content = String::new();
    content.push_str("#!/usr/bin/env bash\nset -euo pipefail\n\n");
    content.push_str(&format!(
        "export KAST_INSTALL_ROOT={}\n",
        shell_quote(&install_root.display().to_string())
    ));
    content.push_str(&format!(
        "export KAST_CONFIG_HOME={}\n\n",
        shell_quote(&config_home.display().to_string())
    ));
    for java_opt in java_opts {
        content.push_str(&format!(
            "case \" ${{JAVA_OPTS:-}} \" in\n  *\" {} \"*) ;;\n  *) export JAVA_OPTS=\"${{JAVA_OPTS:+${{JAVA_OPTS}} }}{}\" ;;\nesac\n",
            java_opt, java_opt
        ));
    }
    content.push('\n');
    content.push_str(&format!(
        "exec {} \"$@\"\n",
        shell_quote(&active_binary.display().to_string())
    ));
    fs::write(shim_path, content)?;
    manifest::make_executable(shim_path)
}

fn write_headless_config(config_file: &Path) -> Result<()> {
    if let Some(parent) = config_file.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(
        config_file,
        r#"[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[runtime]
defaultBackend = "headless"

[backends.headless]
enabled = true
"#,
    )?;
    Ok(())
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
}

pub fn repair_install_state(args: InstallRepairArgs) -> Result<InstallRepairResult> {
    reconcile_install_state(args)
}

fn reconcile_install_state(args: InstallRepairArgs) -> Result<InstallRepairResult> {
    let config_path = config::global_config_path();
    let backup_root = config::kast_config_home()
        .join("backups")
        .join(format!("install-repair-{}", backup_timestamp()));
    let mut result = InstallRepairResult {
        applied: args.apply,
        config_path: config_path.display().to_string(),
        apply_command: "kast doctor --repair".to_string(),
        actions: vec![],
        backups: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    };
    let mut config_backed_up = false;

    if !config_path.is_file() {
        push_repair_action(
            &mut result,
            "provision-config",
            &config_path,
            "Create the global Kast config from current defaults.",
            None,
        );
        if args.apply {
            config::init_config()?;
        }
    }

    let Some(global_config) =
        load_global_config_for_repair(&args, &mut result, &backup_root, &mut config_backed_up)?
    else {
        return Ok(result);
    };
    repair_install_config_state(
        &args,
        &global_config,
        &mut result,
        &backup_root,
        &mut config_backed_up,
    )?;
    repair_install_copilot_repos(&args, &mut result, &backup_root)?;
    repair_install_shell_sources(&args, &mut result, &backup_root)?;
    repair_install_jetbrains_profiles(&args, &mut result, &backup_root)?;

    Ok(result)
}

fn load_global_config_for_repair(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<Option<config::KastConfig>> {
    match config::KastConfig::load_global() {
        Ok(global_config) => Ok(Some(global_config)),
        Err(error) if error.code == "CONFIG_ERROR" => {
            let config_path = config::global_config_path();
            push_repair_action(
                result,
                "recover-invalid-config",
                &config_path,
                "Back up the invalid global Kast config and restore safe defaults.",
                Some("kast doctor --repair".to_string()),
            );
            if !args.apply {
                result.warnings.push(format!(
                    "Global config is invalid at {}; rerun with --apply to back it up and restore safe defaults: {}",
                    config_path.display(),
                    error.message
                ));
                return Ok(None);
            }
            backup_config_once(result, backup_root, config_backed_up)?;
            if let Some(parent) = config_path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&config_path, config::default_config_template()?)?;
            Ok(Some(config::KastConfig::load_global()?))
        }
        Err(error) => Err(error),
    }
}

fn repair_install_config_state(
    args: &InstallRepairArgs,
    global_config: &config::KastConfig,
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    let config_path = config::global_config_path();
    let document = read_toml_document(&config_path)?;
    let remove_paths_table = document.contains_key("paths");
    let remove_cli_table = document.contains_key("cli");
    let remove_install_table = document.contains_key("install");
    let remove_headless_runtime_paths = document
        .get("backends")
        .and_then(toml::Value::as_table)
        .and_then(|backends| backends.get("headless"))
        .and_then(toml::Value::as_table)
        .is_some_and(|headless| {
            headless.contains_key("runtimeLibsDir") || headless.contains_key("ideaHome")
        });

    if remove_paths_table
        || remove_cli_table
        || remove_install_table
        || remove_headless_runtime_paths
    {
        push_repair_action(
            result,
            "remove-install-owned-config",
            &config_path,
            "Remove install-owned TOML keys so install identity and paths resolve only from install.json.",
            None,
        );
    }
    if args.apply
        && (remove_paths_table
            || remove_cli_table
            || remove_install_table
            || remove_headless_runtime_paths)
    {
        backup_config_once(result, backup_root, config_backed_up)?;
        self_mgmt::update_global_config(|document| {
            if remove_paths_table {
                document.remove("paths");
            }
            if remove_cli_table {
                document.remove("cli");
            }
            if remove_install_table {
                document.remove("install");
            }
            if remove_headless_runtime_paths
                && let Some(toml::Value::Table(backends)) = document.get_mut("backends")
                && let Some(toml::Value::Table(headless)) = backends.get_mut("headless")
            {
                headless.remove("runtimeLibsDir");
                headless.remove("ideaHome");
            }
            Ok(())
        })?;
    }

    let Some(mut install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let mut install_changed = false;
    if install.version.trim() != cli::version() {
        push_repair_action(
            result,
            "update-install-version",
            &config_path,
            &format!(
                "Record the running kast CLI version {} in install metadata.",
                cli::version()
            ),
            None,
        );
        install_changed = true;
    }

    let mut surviving_backends = vec![];
    for backend in install.backends {
        let unsupported = backend.name != HEADLESS_BACKEND_NAME;
        let classpath_missing = !Path::new(&backend.runtime_libs_dir)
            .join("classpath.txt")
            .is_file();
        let install_dir_missing = !path_exists_or_symlink(Path::new(&backend.install_dir));
        if unsupported || classpath_missing || install_dir_missing {
            let reason = if unsupported {
                format!(
                    "Remove unsupported {} backend state from install metadata.",
                    backend.name
                )
            } else {
                format!(
                    "Remove backend state whose runtime files are missing at {}.",
                    backend.runtime_libs_dir
                )
            };
            push_repair_action(
                result,
                "remove-stale-backend-state",
                Path::new(&backend.install_dir),
                &reason,
                Some("Reinstall or refresh the Linux headless tarball.".to_string()),
            );
            if args.apply {
                let install_dir = Path::new(&backend.install_dir);
                backup_existing_path(install_dir, backup_root, result)?;
                remove_existing_path(install_dir)?;
            }
            install_changed = true;
        } else {
            surviving_backends.push(backend);
        }
    }
    install.backends = surviving_backends;

    let surviving_backend_components = install
        .backends
        .iter()
        .map(|backend| format!("backend:{}", backend.name))
        .collect::<BTreeSet<_>>();
    let original_components = install.components.clone();
    install.components.retain(|component| {
        !component.starts_with("backend:") || surviving_backend_components.contains(component)
    });
    for component in original_components {
        if !install.components.contains(&component) {
            push_repair_action(
                result,
                "remove-stale-component-state",
                Path::new(&component),
                "Remove install metadata for a backend component that is no longer present.",
                Some("Reinstall or refresh the Linux headless tarball.".to_string()),
            );
            install_changed = true;
        }
    }

    let original_managed_paths = std::mem::take(&mut install.managed_paths);
    for managed_path_value in original_managed_paths {
        let managed = managed_install_path(&global_config.paths.install_root, &managed_path_value);
        if !path_exists_or_symlink(&managed) {
            push_repair_action(
                result,
                "prune-missing-managed-path",
                &managed,
                "Remove a missing managed path from install metadata.",
                None,
            );
            install_changed = true;
            continue;
        }
        install.managed_paths.push(managed_path_value);
    }

    let mut seen_repos = BTreeSet::new();
    let mut deduped_repos = vec![];
    for repo in install.repos {
        let normalized = config::normalize(PathBuf::from(&repo.path));
        let normalized_value = normalized.display().to_string();
        if seen_repos.insert(normalized_value.clone()) {
            deduped_repos.push(self_mgmt::ManagedRepo {
                path: normalized_value,
                copilot_package_version: repo.copilot_package_version,
            });
        } else {
            push_repair_action(
                result,
                "dedupe-managed-repo",
                &normalized,
                "Remove duplicate managed repo install metadata.",
                None,
            );
            install_changed = true;
        }
    }
    install.repos = deduped_repos;

    if install_changed {
        install.version = cli::version().to_string();
        install.installed_at = current_timestamp();
        install.platform = format!("{}-{}", env::consts::OS, env::consts::ARCH);
    }
    if args.apply && install_changed {
        backup_config_once(result, backup_root, config_backed_up)?;
        if install.components.is_empty()
            && install.backends.is_empty()
            && install.managed_paths.is_empty()
            && install.shell_rc_patches.is_empty()
            && install.repos.is_empty()
        {
            self_mgmt::remove_global_install_state()?;
        } else {
            self_mgmt::write_install_state(&install)?;
        }
    }

    Ok(())
}

fn repair_install_copilot_repos(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    _backup_root: &Path,
) -> Result<()> {
    let Some(install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let mut seen = BTreeSet::new();
    for repo in install.repos {
        let repo_root = config::normalize(PathBuf::from(repo.path));
        if !seen.insert(repo_root.display().to_string()) {
            continue;
        }
        let github_dir = repo_root.join(".github");
        let marker = github_dir.join(COPILOT_PACKAGE_MARKER);
        let installed_version = fs::read_to_string(&marker).unwrap_or_default();
        if installed_version.trim() == cli::version() {
            continue;
        }
        push_repair_action(
            result,
            "refresh-copilot-package",
            &github_dir,
            "Refresh a stale managed Copilot LSP package install.",
            Some(format!(
                "kast install copilot --target-dir {} --force",
                shell_quote_path(&github_dir)
            )),
        );
        if args.apply {
            install_copilot(CopilotInstallArgs {
                target_dir: Some(github_dir),
                force: true,
                no_auto_exclude_git: false,
            })?;
        }
    }
    Ok(())
}

fn repair_install_shell_sources(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let shell_dir = config::kast_config_home().join("shell");
    if !shell_dir.is_dir() {
        return Ok(());
    }
    let mut entries = fs::read_dir(&shell_dir)?.collect::<std::result::Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.path());
    for entry in entries {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(shell) = path
            .extension()
            .and_then(|extension| extension.to_str())
            .and_then(|extension| match extension {
                "bash" => Some(ShellKind::Bash),
                "zsh" => Some(ShellKind::Zsh),
                _ => None,
            })
        else {
            continue;
        };
        let Some(command_name) = path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .filter(|name| !name.trim().is_empty())
        else {
            continue;
        };
        validate_shell_command_name(command_name)?;
        let content = fs::read_to_string(&path)?;
        if !content.contains("Managed by `kast install shell`") {
            continue;
        }
        let Some(bin_dir) = resolve_command_bin_dir(command_name)? else {
            result.warnings.push(format!(
                "Could not resolve `{command_name}` on PATH; leaving managed shell source {} unchanged",
                path.display()
            ));
            continue;
        };
        if content.contains(&format!(
            "_kast_bin_dir={}",
            shell_quote(&bin_dir.display().to_string())
        )) {
            continue;
        }
        push_repair_action(
            result,
            "refresh-shell-source",
            &path,
            &format!(
                "Back up and rewrite managed shell integration for `{command_name}` to use {}.",
                bin_dir.display()
            ),
            Some("kast doctor --repair".to_string()),
        );
        if args.apply {
            backup_existing_path(&path, backup_root, result)?;
            fs::write(
                &path,
                shell_source_content(shell, command_name, &bin_dir, &config::kast_config_home()),
            )?;
        }
    }
    Ok(())
}

fn repair_install_jetbrains_profiles(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let Some(expected_plugin_target) = expected_homebrew_plugin_target(result)? else {
        return Ok(());
    };
    let jetbrains_config_root = args
        .jetbrains_config_root
        .clone()
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    for plugin_dir in jetbrains_plugin_dirs(&jetbrains_config_root)? {
        let plugin_link = plugin_dir.join("kast");
        if !path_exists_or_symlink(&plugin_link) {
            continue;
        }
        if fs::read_link(&plugin_link)
            .ok()
            .is_some_and(|target| target == expected_plugin_target)
        {
            continue;
        }
        push_repair_action(
            result,
            "refresh-idea-plugin-link",
            &plugin_link,
            &format!(
                "Back up and relink a stale IDEA or Android Studio profile plugin to {}.",
                expected_plugin_target.display()
            ),
            Some("kast install plugin --force".to_string()),
        );
        if args.apply {
            backup_existing_path(&plugin_link, backup_root, result)?;
            remove_existing_path(&plugin_link)?;
            if let Some(parent) = plugin_link.parent() {
                fs::create_dir_all(parent)?;
            }
            create_plugin_link(&expected_plugin_target, &plugin_link, &mut result.warnings)?;
        }
    }
    Ok(())
}

fn push_repair_action(
    result: &mut InstallRepairResult,
    kind: &str,
    target: &Path,
    message: &str,
    command: Option<String>,
) {
    result.actions.push(InstallRepairAction {
        kind: kind.to_string(),
        target: target.display().to_string(),
        status: if result.applied { "applied" } else { "planned" }.to_string(),
        message: message.to_string(),
        command,
    });
}

fn backup_config_once(
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    if *config_backed_up {
        return Ok(());
    }
    backup_existing_path(&config::global_config_path(), backup_root, result)?;
    *config_backed_up = true;
    Ok(())
}

fn backup_existing_path(
    path: &Path,
    backup_root: &Path,
    result: &mut InstallRepairResult,
) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    fs::create_dir_all(backup_root)?;
    let backup_path = backup_root.join(format!(
        "{:03}-{}",
        result.backups.len() + 1,
        backup_label(path)
    ));
    if metadata.file_type().is_symlink() {
        let target = fs::read_link(path)?;
        fs::write(
            &backup_path,
            format!("symlink {}\n", target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        fs::copy(path, &backup_path)?;
    } else if metadata.is_dir() {
        copy_path_recursive(path, &backup_path)?;
    }
    result.backups.push(backup_path.display().to_string());
    Ok(())
}

fn copy_path_recursive(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        let link_target = fs::read_link(source)?;
        fs::write(
            target,
            format!("symlink {}\n", link_target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
    } else if metadata.is_dir() {
        fs::create_dir_all(target)?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            copy_path_recursive(&entry.path(), &target.join(entry.file_name()))?;
        }
    }
    Ok(())
}

fn backup_label(path: &Path) -> String {
    let raw = path
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("path");
    let sanitized = raw
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect::<String>();
    if sanitized.is_empty() {
        "path".to_string()
    } else {
        sanitized
    }
}

fn remove_existing_path(path: &Path) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    if metadata.file_type().is_symlink() || metadata.is_file() {
        fs::remove_file(path)?;
    } else if metadata.is_dir() {
        fs::remove_dir_all(path)?;
    }
    Ok(())
}

fn path_exists_or_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

pub(crate) fn kast_idea_plugin_installed() -> Result<bool> {
    let jetbrains_config_root = env::var_os("KAST_JETBRAINS_CONFIG_ROOT")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    kast_idea_plugin_installed_under(&jetbrains_config_root)
}

pub(crate) fn kast_idea_plugin_installed_under(jetbrains_config_root: &Path) -> Result<bool> {
    Ok(jetbrains_plugin_dirs(jetbrains_config_root)?
        .into_iter()
        .any(|plugin_dir| path_exists_or_symlink(&plugin_dir.join("kast"))))
}

fn managed_install_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn read_toml_document(path: &Path) -> Result<toml::Table> {
    if !path.is_file() {
        return Ok(toml::Table::new());
    }
    Ok(fs::read_to_string(path)?.parse::<toml::Table>()?)
}

fn resolve_command_bin_dir(command_name: &str) -> Result<Option<PathBuf>> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
    {
        return Ok(current_exe.parent().map(Path::to_path_buf));
    }
    let output = ProcessCommand::new("which").arg(command_name).output();
    let Ok(output) = output else {
        return Ok(None);
    };
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let command_path = stdout
        .lines()
        .next()
        .map(str::trim)
        .filter(|line| !line.is_empty());
    Ok(command_path
        .map(PathBuf::from)
        .and_then(|path| path.parent().map(Path::to_path_buf)))
}

fn expected_homebrew_plugin_target(result: &mut InstallRepairResult) -> Result<Option<PathBuf>> {
    expected_homebrew_plugin_target_with_warnings(&mut result.warnings)
}

fn expected_homebrew_plugin_target_with_warnings(
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let brew_prefix = match homebrew_prefix(&["--prefix"]) {
        Ok(value) => value,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve Homebrew prefix; skipping JetBrains plugin link repair: {}",
                error.message
            ));
            return Ok(None);
        }
    };
    let formula_tap = homebrew_formula_tap().unwrap_or_else(|error| {
        warnings.push(format!(
            "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
            error.message
        ));
        DEFAULT_KAST_TAP.to_string()
    });
    let cask_token = format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}");
    let cask_name = cask_name(&cask_token);
    expected_homebrew_plugin_target_for_cask(&cask_name, &brew_prefix, warnings)
}

fn expected_homebrew_plugin_target_for_cask(
    cask_name: &str,
    brew_prefix: &Path,
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let Some(version) = homebrew_cask_version(cask_name)? else {
        warnings.push(format!(
            "Homebrew cask {cask_name} is not installed; skipping JetBrains plugin link repair"
        ));
        return Ok(None);
    };
    Ok(Some(
        brew_prefix
            .join("Caskroom")
            .join(cask_name)
            .join(version)
            .join("backend-idea"),
    ))
}

#[cfg(unix)]
fn create_plugin_link(source: &Path, target: &Path, _warnings: &mut Vec<String>) -> Result<()> {
    std::os::unix::fs::symlink(source, target)?;
    Ok(())
}

#[cfg(not(unix))]
fn create_plugin_link(_source: &Path, target: &Path, warnings: &mut Vec<String>) -> Result<()> {
    warnings.push(format!(
        "Cannot create JetBrains plugin symlink on this platform; left {} unchanged",
        target.display()
    ));
    Ok(())
}

fn backup_timestamp() -> String {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs().to_string())
        .unwrap_or_else(|_| "0".to_string())
}

pub fn install_skill(args: ResourceInstallArgs) -> Result<InstallSkillResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_skill_target_dir);
    let name = args
        .name
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let skipped = install_dir(&KAST_SKILL, &target, ".kast-version", args.force)?;
    Ok(InstallSkillResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_instructions(args: ResourceInstallArgs) -> Result<InstallInstructionsResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_instructions_target_dir);
    let name = args
        .name
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let skipped = install_dir(&KAST_INSTRUCTIONS, &target, ".kast-version", args.force)?;
    Ok(InstallInstructionsResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_copilot(args: CopilotInstallArgs) -> Result<InstallCopilotPackageResult> {
    let target = args.target_dir.map(config::normalize).unwrap_or_else(|| {
        config::normalize(
            env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".github"),
        )
    });
    let skipped = install_copilot_package_files(&target, args.force)?;
    self_mgmt::record_copilot_repo(&target, cli::version())?;
    let git_exclude = update_copilot_git_exclude(&target, args.no_auto_exclude_git)?;
    Ok(InstallCopilotPackageResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        git_exclude,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_idea_plugin(
    args: IdeaPluginInstallArgs,
    reporter: &mut dyn InstallReporter,
) -> Result<InstallIdeaPluginResult> {
    let homebrew = discover_homebrew_context()?;
    verify_homebrew_cli(&homebrew)?;
    let mut warnings = vec![];
    let formula_tap = match homebrew_formula_tap() {
        Ok(tap) => tap,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
                error.message
            ));
            DEFAULT_KAST_TAP.to_string()
        }
    };
    let cask_token = args
        .cask_token
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}"));
    install_idea_plugin_into_jetbrains_profiles(args, homebrew, cask_token, warnings, reporter)
}

pub fn install_shell(args: ShellInstallArgs) -> Result<InstallShellResult> {
    let config = config::KastConfig::load_global()?;
    let config_home = config::kast_config_home();
    let shell = args.shell.map(Ok).unwrap_or_else(detect_shell)?;
    let command_name = args
        .command_name
        .unwrap_or_else(default_shell_command_name)
        .trim()
        .to_string();
    validate_shell_command_name(&command_name)?;
    let bin_dir = shell_integration_bin_dir(&command_name, &config.paths.bin_dir)?;
    let source_file = args.source_file.map(config::normalize).unwrap_or_else(|| {
        config_home
            .join("shell")
            .join(format!("{command_name}.{}", shell.extension()))
    });
    let profile = args
        .profile
        .map(config::normalize)
        .unwrap_or_else(|| default_shell_profile(shell));
    let source_line = format!("source {}", shell_quote_path(&source_file));
    let source_content = shell_source_content(shell, &command_name, &bin_dir, &config_home);

    if !args.dry_run {
        if let Some(parent) = source_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&source_file, source_content)?;
    }
    let profile_updated = patch_shell_profile(&profile, &source_line, args.dry_run)?;

    Ok(InstallShellResult {
        shell: shell.canonical().to_string(),
        command_name,
        bin_dir: bin_dir.display().to_string(),
        config_home: config_home.display().to_string(),
        source_file: source_file.display().to_string(),
        profile: profile.display().to_string(),
        profile_updated,
        dry_run: args.dry_run,
        source_line,
        schema_version: SCHEMA_VERSION,
    })
}

fn detect_shell() -> Result<ShellKind> {
    let shell = env::var_os("SHELL")
        .and_then(|value| PathBuf::from(value).file_name().map(|name| name.to_owned()))
        .and_then(|name| name.to_str().map(str::to_string))
        .unwrap_or_default();
    match shell.as_str() {
        "bash" => Ok(ShellKind::Bash),
        "zsh" => Ok(ShellKind::Zsh),
        _ => Err(CliError::new(
            "CLI_USAGE",
            "Could not infer a supported shell from SHELL. Pass `kast install shell --shell bash` or `--shell zsh`.",
        )),
    }
}

fn default_shell_command_name() -> String {
    env::current_exe()
        .ok()
        .and_then(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .map(str::to_string)
        })
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn shell_integration_bin_dir(command_name: &str, configured_bin_dir: &Path) -> Result<PathBuf> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
        && let Some(parent) = current_exe.parent()
    {
        return Ok(parent.to_path_buf());
    }
    Ok(resolve_command_bin_dir(command_name)?.unwrap_or_else(|| configured_bin_dir.to_path_buf()))
}

fn default_shell_profile(shell: ShellKind) -> PathBuf {
    match shell {
        ShellKind::Bash => config::home_dir().join(".bashrc"),
        ShellKind::Zsh => config::home_dir().join(".zshrc"),
    }
}

fn validate_shell_command_name(command_name: &str) -> Result<()> {
    let valid = !command_name.is_empty()
        && command_name
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || matches!(char, '-' | '_' | '.' | '+'));
    if valid {
        return Ok(());
    }
    let mut error = CliError::new(
        "CLI_USAGE",
        "Shell command name must contain only ASCII letters, digits, dash, underscore, dot, or plus.",
    );
    error
        .details
        .insert("commandName".to_string(), command_name.to_string());
    Err(error)
}

fn shell_source_content(
    shell: ShellKind,
    command_name: &str,
    bin_dir: &Path,
    config_home: &Path,
) -> String {
    format!(
        r#"# Managed by `kast install shell`; re-run that command after moving Kast.
export KAST_CONFIG_HOME={}
_kast_bin_dir={}
case ":${{PATH}}:" in
  *":${{_kast_bin_dir}}:"*) ;;
  *) export PATH="${{_kast_bin_dir}}:${{PATH}}" ;;
esac
unset _kast_bin_dir

if command -v {command_name} >/dev/null 2>&1; then
  source <({command_name} install completion {} --command-name {command_name})
fi
"#,
        shell_quote(&config_home.display().to_string()),
        shell_quote(&bin_dir.display().to_string()),
        shell.canonical(),
    )
}

fn patch_shell_profile(profile: &Path, source_line: &str, dry_run: bool) -> Result<bool> {
    let block = format!(
        "{SHELL_BLOCK_START}\n# Managed by `kast install shell`; edit the generated source file instead.\n{source_line}\n{SHELL_BLOCK_END}\n"
    );
    let original = match fs::read_to_string(profile) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let updated = replace_managed_block(&original, &block);
    if updated == original {
        return Ok(false);
    }
    if !dry_run {
        if let Some(parent) = profile.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(profile, updated)?;
    }
    Ok(true)
}

fn replace_managed_block(original: &str, block: &str) -> String {
    replace_managed_block_with_markers(original, block, SHELL_BLOCK_START, SHELL_BLOCK_END)
}

fn replace_managed_block_with_markers(
    original: &str,
    block: &str,
    start_marker: &str,
    end_marker: &str,
) -> String {
    if let Some(start) = original.find(start_marker)
        && let Some(end_offset) = original[start..].find(end_marker)
    {
        let end = start + end_offset + end_marker.len();
        let mut updated = String::new();
        updated.push_str(&original[..start]);
        updated.push_str(block);
        updated.push_str(original[end..].trim_start_matches('\n'));
        return updated;
    }
    let mut updated = original.to_string();
    if !updated.is_empty() && !updated.ends_with('\n') {
        updated.push('\n');
    }
    if !updated.is_empty() {
        updated.push('\n');
    }
    updated.push_str(block);
    updated
}

fn shell_quote_path(path: &Path) -> String {
    shell_quote(&path.display().to_string())
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn install_idea_plugin_into_jetbrains_profiles(
    args: IdeaPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    mut warnings: Vec<String>,
    reporter: &mut dyn InstallReporter,
) -> Result<InstallIdeaPluginResult> {
    let jetbrains_config_root = args
        .jetbrains_config_root
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    let plugin_directories = jetbrains_plugin_dirs(&jetbrains_config_root)?;
    if plugin_directories.is_empty() {
        let mut error = CliError::new(
            "JETBRAINS_CONFIG_NOT_FOUND",
            format!(
                "No JetBrains IDE profile directories were found under {}",
                jetbrains_config_root.display()
            ),
        );
        error.details.insert(
            "expectedRoot".to_string(),
            jetbrains_config_root.display().to_string(),
        );
        return Err(error);
    }

    let cask_name = cask_name(&cask_token);
    let cask_installed = homebrew_cask_installed(&cask_name)?;
    let brew_action = if cask_installed {
        "reinstall"
    } else {
        "install"
    };
    let mut brew_args = vec![
        brew_action.to_string(),
        "--cask".to_string(),
        cask_token.clone(),
    ];
    if args.force {
        brew_args.insert(2, "--force".to_string());
    }
    let download_plan = homebrew_cask_download_plan(&cask_token, &plugin_directories)?;
    reporter.idea_plugin_plan(&download_plan)?;
    let downloaded_bytes = if args.dry_run {
        file_size(&download_plan.download_cache).unwrap_or(0)
    } else {
        prefetch_homebrew_cask(
            &download_plan.cask_token,
            args.force,
            &download_plan.download_cache,
            reporter,
        )?
    };
    if !args.dry_run {
        let output = run_brew_with_jetbrains_root(&brew_args, &jetbrains_config_root)?;
        if !output.status.success() {
            return Err(command_error(
                "HOMEBREW_CASK_INSTALL_FAILED",
                "Homebrew failed to install the Kast IDEA plugin cask",
                &brew_args,
                &output,
            ));
        }
        ensure_homebrew_plugin_profile_links(
            &homebrew,
            &cask_name,
            &plugin_directories,
            &mut warnings,
        )?;
    }

    Ok(InstallIdeaPluginResult {
        cask_token,
        plugin_version: download_plan.plugin_version,
        download_cache: download_plan.download_cache.display().to_string(),
        downloaded_bytes,
        brew_action: brew_action.to_string(),
        brew_command: std::iter::once("brew".to_string())
            .chain(brew_args)
            .collect(),
        brew_prefix: homebrew.brew_prefix.display().to_string(),
        formula_prefix: homebrew.formula_prefix.display().to_string(),
        cli_path: homebrew.cli_path.display().to_string(),
        jetbrains_config_root: Some(jetbrains_config_root.display().to_string()),
        plugin_directories: plugin_directories
            .into_iter()
            .map(|path| path.display().to_string())
            .collect(),
        dry_run: args.dry_run,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn ensure_homebrew_plugin_profile_links(
    homebrew: &HomebrewContext,
    cask_name: &str,
    plugin_directories: &[PathBuf],
    warnings: &mut Vec<String>,
) -> Result<()> {
    let Some(expected_plugin_target) =
        expected_homebrew_plugin_target_for_cask(cask_name, &homebrew.brew_prefix, warnings)?
    else {
        return Ok(());
    };
    for plugin_dir in plugin_directories {
        let plugin_link = plugin_dir.join("kast");
        ensure_homebrew_plugin_profile_link(&expected_plugin_target, &plugin_link, warnings)?;
    }
    Ok(())
}

fn ensure_homebrew_plugin_profile_link(
    expected_plugin_target: &Path,
    plugin_link: &Path,
    warnings: &mut Vec<String>,
) -> Result<()> {
    if fs::read_link(plugin_link)
        .ok()
        .is_some_and(|target| target == expected_plugin_target)
    {
        return Ok(());
    }
    if path_exists_or_symlink(plugin_link) {
        let Some(current_target) = fs::read_link(plugin_link).ok() else {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin path {}; run `kast doctor --repair` for backed-up repair",
                plugin_link.display()
            ));
            return Ok(());
        };
        if !current_target
            .display()
            .to_string()
            .contains("/Caskroom/kast-plugin/")
            && !current_target
                .display()
                .to_string()
                .contains("/kast-plugin/")
        {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin link {} -> {}; run `kast doctor --repair` for backed-up repair",
                plugin_link.display(),
                current_target.display()
            ));
            return Ok(());
        }
        remove_existing_path(plugin_link)?;
    }
    if let Some(parent) = plugin_link.parent() {
        fs::create_dir_all(parent)?;
    }
    create_plugin_link(expected_plugin_target, plugin_link, warnings)?;
    Ok(())
}

fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}

fn install_copilot_package_files(github_dir: &Path, force: bool) -> Result<bool> {
    let marker = github_dir.join(COPILOT_PACKAGE_MARKER);
    let skipped = !force
        && marker
            .is_file()
            .then(|| fs::read_to_string(&marker).unwrap_or_default())
            .is_some_and(|current| current.trim() == cli::version());
    if skipped {
        return Ok(true);
    }
    let replace_managed = force || marker.is_file();
    for output in copilot_package_outputs()? {
        write_copilot_package_file(
            github_dir,
            &output.target,
            output.contents,
            replace_managed,
            output.executable,
        )?;
    }
    fs::write(marker, format!("{}\n", cli::version()))?;
    Ok(false)
}

fn update_copilot_git_exclude(github_dir: &Path, disabled: bool) -> Result<GitExcludeResult> {
    if disabled {
        return Ok(GitExcludeResult {
            attempted: false,
            updated: false,
            exclude_file: None,
            reason: Some("disabled".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    }
    let repo_root = github_dir.parent().unwrap_or(github_dir);
    let Some(exclude_file) = git_info_exclude_path(repo_root) else {
        return Ok(GitExcludeResult {
            attempted: false,
            updated: false,
            exclude_file: None,
            reason: Some("not a git repository".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    };
    let entries = copilot_git_exclude_entries(repo_root, github_dir)?;
    let block = format!(
        "{COPILOT_GIT_EXCLUDE_BLOCK_START}\n{}\n{COPILOT_GIT_EXCLUDE_BLOCK_END}\n",
        entries.join("\n")
    );
    let original = match fs::read_to_string(&exclude_file) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let updated_content = replace_managed_block_with_markers(
        &original,
        &block,
        COPILOT_GIT_EXCLUDE_BLOCK_START,
        COPILOT_GIT_EXCLUDE_BLOCK_END,
    );
    let updated = updated_content != original;
    if updated {
        if let Some(parent) = exclude_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&exclude_file, updated_content)?;
    }
    Ok(GitExcludeResult {
        attempted: true,
        updated,
        exclude_file: Some(exclude_file.display().to_string()),
        reason: None,
        schema_version: SCHEMA_VERSION,
    })
}

fn git_info_exclude_path(repo_root: &Path) -> Option<PathBuf> {
    let output = ProcessCommand::new("git")
        .arg("-C")
        .arg(repo_root)
        .args(["rev-parse", "--git-path", "info/exclude"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8(output.stdout).ok()?;
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    let path = PathBuf::from(raw);
    Some(if path.is_absolute() {
        path
    } else {
        repo_root.join(path)
    })
}

fn copilot_git_exclude_entries(repo_root: &Path, github_dir: &Path) -> Result<Vec<String>> {
    let mut entries = copilot_package_outputs()?
        .into_iter()
        .map(|output| github_dir.join(output.target))
        .chain(std::iter::once(github_dir.join(COPILOT_PACKAGE_MARKER)))
        .map(|path| path_to_git_exclude_entry(repo_root, &path))
        .collect::<Result<Vec<_>>>()?;
    entries.sort();
    entries.dedup();
    Ok(entries)
}

fn path_to_git_exclude_entry(repo_root: &Path, path: &Path) -> Result<String> {
    let relative = path.strip_prefix(repo_root).map_err(|_| {
        CliError::new(
            "INSTALL_TARGET_OUTSIDE_GIT_REPO",
            format!(
                "Managed Copilot package path {} is not under Git repository {}",
                path.display(),
                repo_root.display()
            ),
        )
    })?;
    Ok(relative
        .components()
        .map(|component| component.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/"))
}

struct CopilotPackageOutput {
    target: PathBuf,
    contents: &'static [u8],
    executable: bool,
}

fn copilot_package_outputs() -> Result<Vec<CopilotPackageOutput>> {
    let manifest = embedded_file_contents(&COPILOT_PLUGIN, COPILOT_PRIMITIVE_MANIFEST)?;
    let manifest: Value = serde_json::from_slice(manifest)?;
    let outputs = manifest
        .get("outputs")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            CliError::new(
                "COPILOT_PACKAGE_MANIFEST_INVALID",
                "Copilot primitive manifest must contain an outputs array.",
            )
        })?;
    let mut resolved = Vec::with_capacity(outputs.len());
    for output in outputs {
        let output_type = manifest_string_field(output, "type")?;
        let source = manifest_string_field(output, "source")?;
        let target = validate_manifest_relative_path(manifest_string_field(output, "target")?)?;
        let source = validate_manifest_relative_path(source)?;
        let source_path = source.to_string_lossy();
        let contents = match output_type {
            "PACKAGE_FILE" => embedded_file_contents(&COPILOT_PLUGIN, &source_path)?,
            "KAST_SKILL_FILE" => embedded_file_contents(&KAST_SKILL, &source_path)?,
            other => {
                return Err(CliError::new(
                    "COPILOT_PACKAGE_MANIFEST_INVALID",
                    format!("Unsupported Copilot package output type `{other}`."),
                ));
            }
        };
        resolved.push(CopilotPackageOutput {
            target,
            contents,
            executable: output
                .get("executable")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        });
    }
    Ok(resolved)
}

fn embedded_file_contents(dir: &'static Dir<'static>, relative: &str) -> Result<&'static [u8]> {
    dir.get_file(relative)
        .map(|file| file.contents())
        .ok_or_else(|| {
            CliError::new(
                "COPILOT_PACKAGE_SOURCE_MISSING",
                format!("Embedded Copilot package source `{relative}` was not found."),
            )
        })
}

fn manifest_string_field<'a>(value: &'a Value, field: &str) -> Result<&'a str> {
    value.get(field).and_then(Value::as_str).ok_or_else(|| {
        CliError::new(
            "COPILOT_PACKAGE_MANIFEST_INVALID",
            format!("Copilot package output must contain string field `{field}`."),
        )
    })
}

fn validate_manifest_relative_path(value: &str) -> Result<PathBuf> {
    let path = Path::new(value);
    let safe = !value.trim().is_empty()
        && path
            .components()
            .all(|component| matches!(component, Component::Normal(_) | Component::CurDir));
    if safe && !path.is_absolute() {
        return Ok(path.to_path_buf());
    }
    Err(CliError::new(
        "COPILOT_PACKAGE_MANIFEST_INVALID",
        format!("Manifest path `{value}` must be relative and must not contain `..`."),
    ))
}

fn write_copilot_package_file(
    github_dir: &Path,
    relative: &Path,
    contents: &[u8],
    force: bool,
    executable: bool,
) -> Result<()> {
    let target = github_dir.join(relative);
    if target.exists() && !force {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} already exists. Pass --force to replace it.",
                target.display()
            ),
        ));
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(&target, contents)?;
    if executable {
        set_executable(&target)?;
    } else {
        set_executable_if_script(&target)?;
    }
    Ok(())
}

fn install_dir(dir: &Dir<'_>, target: &Path, marker_name: &str, force: bool) -> Result<bool> {
    let marker = target.join(marker_name);
    if marker.is_file() {
        let current = fs::read_to_string(&marker).unwrap_or_default();
        if !force && current.trim() == cli::version() {
            return Ok(true);
        }
    }
    if target.exists() && !force {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} already exists. Pass --force to replace it.",
                target.display()
            ),
        ));
    }
    if target.exists() {
        fs::remove_dir_all(target)?;
    }
    fs::create_dir_all(target)?;
    copy_dir_entries(dir, target)?;
    fs::write(marker, format!("{}\n", cli::version()))?;
    Ok(false)
}

fn copy_dir_entries(dir: &Dir<'_>, target: &Path) -> Result<()> {
    for entry in dir.entries() {
        copy_entry(entry, target)?;
    }
    Ok(())
}

fn copy_entry(entry: &DirEntry<'_>, target_root: &Path) -> Result<()> {
    match entry {
        DirEntry::Dir(dir) => {
            let target = target_root.join(dir.path());
            fs::create_dir_all(&target)?;
            for child in dir.entries() {
                copy_entry(child, target_root)?;
            }
        }
        DirEntry::File(file) => {
            let target = target_root.join(file.path());
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&target, file.contents())?;
            set_executable_if_script(&target)?;
        }
    }
    Ok(())
}
#[cfg(unix)]
fn set_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
fn set_executable(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_executable_if_script(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let executable = path
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| matches!(extension, "sh" | "py" | "mjs"));
    if executable {
        let mut permissions = fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_executable_if_script(_path: &Path) -> Result<()> {
    Ok(())
}

#[derive(Debug)]
struct HomebrewContext {
    brew_prefix: PathBuf,
    formula_prefix: PathBuf,
    cli_path: PathBuf,
}

#[derive(Debug)]
struct JetBrainsPluginDir {
    product: String,
    year: u32,
    minor: u32,
    patch: u32,
    path: PathBuf,
}

fn discover_homebrew_context() -> Result<HomebrewContext> {
    let brew_prefix = homebrew_prefix(&["--prefix"])?;
    let formula_prefix = homebrew_prefix(&["--prefix", KAST_FORMULA_NAME])?;
    let cli_path = env::current_exe()?;
    Ok(HomebrewContext {
        brew_prefix,
        formula_prefix,
        cli_path,
    })
}

fn verify_homebrew_cli(homebrew: &HomebrewContext) -> Result<()> {
    if path_is_below_homebrew_formula(&homebrew.cli_path, &homebrew.formula_prefix) {
        return Ok(());
    }
    let mut error = CliError::new(
        "HOMEBREW_INSTALL_REQUIRED",
        format!(
            "`kast install plugin` must be run from the Homebrew-installed kast binary under {}",
            homebrew.formula_prefix.display()
        ),
    );
    error.details.insert(
        "cliPath".to_string(),
        homebrew.cli_path.display().to_string(),
    );
    error.details.insert(
        "formulaPrefix".to_string(),
        homebrew.formula_prefix.display().to_string(),
    );
    Err(error)
}

fn homebrew_prefix(args: &[&str]) -> Result<PathBuf> {
    let output = run_brew(args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew did not report the expected install prefix",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let prefix = stdout.trim();
    if prefix.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew returned an empty install prefix",
        ));
    }
    Ok(PathBuf::from(prefix))
}

fn homebrew_formula_tap() -> Result<String> {
    let args = ["info", "--json=v2", KAST_FORMULA_NAME];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew did not report metadata for the kast formula",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_homebrew_formula_tap(&stdout).ok_or_else(|| {
        CliError::new(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew metadata did not include a tap for the kast formula",
        )
    })
}

fn parse_homebrew_formula_tap(json: &str) -> Option<String> {
    let value: Value = serde_json::from_str(json).ok()?;
    value
        .get("formulae")?
        .as_array()?
        .first()?
        .get("tap")?
        .as_str()
        .map(str::trim)
        .filter(|tap| !tap.is_empty())
        .map(str::to_string)
}

fn homebrew_cask_download_plan(
    cask_token: &str,
    plugin_directories: &[PathBuf],
) -> Result<IdeaPluginDownloadPlan> {
    Ok(IdeaPluginDownloadPlan {
        cask_token: cask_token.to_string(),
        plugin_version: homebrew_cask_metadata(cask_token)?.plugin_version,
        download_cache: homebrew_cask_cache_path(cask_token)?,
        plugin_directories: plugin_directories.to_vec(),
    })
}

struct HomebrewCaskMetadata {
    plugin_version: String,
}

fn homebrew_cask_metadata(cask_token: &str) -> Result<HomebrewCaskMetadata> {
    let args = ["info", "--json=v2", "--cask", cask_token];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_CASK_METADATA_FAILED",
            "Homebrew did not report metadata for the Kast IDEA plugin cask",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_homebrew_cask_metadata(&stdout).ok_or_else(|| {
        CliError::new(
            "HOMEBREW_CASK_METADATA_FAILED",
            "Homebrew cask metadata did not include a plugin version.",
        )
    })
}

fn parse_homebrew_cask_metadata(json: &str) -> Option<HomebrewCaskMetadata> {
    let value: Value = serde_json::from_str(json).ok()?;
    let version = value
        .get("casks")?
        .as_array()?
        .first()?
        .get("version")?
        .as_str()?
        .trim();
    (!version.is_empty()).then(|| HomebrewCaskMetadata {
        plugin_version: version.to_string(),
    })
}

fn homebrew_cask_cache_path(cask_token: &str) -> Result<PathBuf> {
    let args = ["--cache", "--cask", cask_token];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew did not report the Kast IDEA plugin cask cache path",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let path = stdout.trim();
    if path.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew returned an empty cask cache path.",
        ));
    }
    Ok(PathBuf::from(path))
}

fn prefetch_homebrew_cask(
    cask_token: &str,
    force: bool,
    download_cache: &Path,
    reporter: &mut dyn InstallReporter,
) -> Result<u64> {
    let mut args = vec!["fetch".to_string(), "--cask".to_string()];
    if force {
        args.push("--force".to_string());
    }
    args.push(cask_token.to_string());

    let mut child = ProcessCommand::new("brew")
        .args(&args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| {
            CliError::new(
                "HOMEBREW_NOT_FOUND",
                format!("Unable to run `brew`: {error}"),
            )
        })?;

    loop {
        if let Some(status) = child.try_wait()? {
            let downloaded_bytes = file_size(download_cache).unwrap_or(0);
            reporter.idea_plugin_download_progress(downloaded_bytes)?;
            reporter.idea_plugin_download_finished(downloaded_bytes)?;
            if !status.success() {
                let mut error = CliError::new(
                    "HOMEBREW_CASK_FETCH_FAILED",
                    "Homebrew failed to fetch the Kast IDEA plugin cask.",
                );
                error
                    .details
                    .insert("command".to_string(), format!("brew {}", args.join(" ")));
                return Err(error);
            }
            return Ok(downloaded_bytes);
        }
        reporter.idea_plugin_download_progress(file_size(download_cache).unwrap_or(0))?;
        thread::sleep(Duration::from_millis(100));
    }
}

fn file_size(path: &Path) -> Option<u64> {
    fs::metadata(path).ok().map(|metadata| metadata.len())
}

fn homebrew_cask_installed(cask_name: &str) -> Result<bool> {
    let output = run_brew(&["list", "--cask", cask_name])?;
    Ok(output.status.success())
}

fn homebrew_cask_version(cask_name: &str) -> Result<Option<String>> {
    let output = run_brew(&["list", "--cask", "--versions", cask_name])?;
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_homebrew_cask_version(&stdout, cask_name))
}

fn parse_homebrew_cask_version(output: &str, cask_name: &str) -> Option<String> {
    output.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        let name = parts.next()?;
        if name != cask_name {
            return None;
        }
        let version = parts.collect::<Vec<_>>().join(" ");
        (!version.trim().is_empty()).then_some(version)
    })
}

fn run_brew(args: &[&str]) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command.args(args);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn run_brew_with_jetbrains_root(args: &[String], jetbrains_config_root: &Path) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command
        .args(args)
        .env("KAST_JETBRAINS_CONFIG_ROOT", jetbrains_config_root);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn command_error(code: &'static str, message: &str, args: &[String], output: &Output) -> CliError {
    let mut error = CliError::new(code, message);
    error
        .details
        .insert("command".to_string(), format!("brew {}", args.join(" ")));
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !stdout.is_empty() {
        error.details.insert("stdout".to_string(), stdout);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if !stderr.is_empty() {
        error.details.insert("stderr".to_string(), stderr);
    }
    error
}

fn path_is_below_homebrew_formula(path: &Path, formula_prefix: &Path) -> bool {
    let canonical_path = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let canonical_formula_prefix =
        fs::canonicalize(formula_prefix).unwrap_or_else(|_| formula_prefix.to_path_buf());
    canonical_path.starts_with(&canonical_formula_prefix) || path.starts_with(formula_prefix)
}

fn default_jetbrains_config_root() -> PathBuf {
    config::home_dir().join("Library/Application Support/JetBrains")
}

fn jetbrains_plugin_dirs(root: &Path) -> Result<Vec<PathBuf>> {
    if !root.is_dir() {
        return Ok(vec![]);
    }
    let mut dirs = vec![];
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let Some((product, year, minor, patch)) = parse_jetbrains_profile_name(name) else {
            continue;
        };
        dirs.push(JetBrainsPluginDir {
            product,
            year,
            minor,
            patch,
            path: path.join("plugins"),
        });
    }
    dirs.sort_by(|left, right| {
        left.product
            .cmp(&right.product)
            .then_with(|| right.year.cmp(&left.year))
            .then_with(|| right.minor.cmp(&left.minor))
            .then_with(|| right.patch.cmp(&left.patch))
            .then_with(|| {
                left.path
                    .display()
                    .to_string()
                    .cmp(&right.path.display().to_string())
            })
    });
    Ok(dirs.into_iter().map(|dir| dir.path).collect())
}

fn parse_jetbrains_profile_name(name: &str) -> Option<(String, u32, u32, u32)> {
    let (version_start, _) = name.char_indices().find(|(_, ch)| ch.is_ascii_digit())?;
    let product = &name[..version_start];
    let first = product.chars().next()?;
    if !first.is_ascii_alphabetic() || !product.chars().all(|ch| ch.is_ascii_alphanumeric()) {
        return None;
    }
    let rest = &name[version_start..];
    let mut parts = rest.split('.');
    let year = parse_fixed_digits(parts.next()?, 4)?;
    let minor = parse_digits(parts.next()?)?;
    let patch = match parts.next() {
        Some(value) => parse_digits(value)?,
        None => 0,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((product.to_string(), year, minor, patch))
}

fn parse_fixed_digits(value: &str, len: usize) -> Option<u32> {
    if value.len() != len {
        return None;
    }
    parse_digits(value)
}

fn parse_digits(value: &str) -> Option<u32> {
    if value.is_empty() || !value.chars().all(|ch| ch.is_ascii_digit()) {
        return None;
    }
    value.parse().ok()
}

fn cask_name(cask_token: &str) -> String {
    cask_token
        .rsplit('/')
        .next()
        .unwrap_or(cask_token)
        .to_string()
}

fn default_skill_target_dir() -> PathBuf {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for candidate in [".agents/skills", ".github/skills", ".claude/skills"] {
        let path = cwd.join(candidate);
        if path.is_dir() {
            return config::normalize(path);
        }
    }
    manifest::resolve_paths()
        .unwrap_or_else(|_| manifest::default_resolved_paths())
        .lib_dir
        .join("skills")
}

fn default_instructions_target_dir() -> PathBuf {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for candidate in [
        ".agents/instructions",
        ".github/instructions",
        ".claude/instructions",
    ] {
        let path = cwd.join(candidate);
        if path.is_dir() {
            return config::normalize(path);
        }
    }
    manifest::resolve_paths()
        .unwrap_or_else(|_| manifest::default_resolved_paths())
        .lib_dir
        .join("instructions")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn install_skill_writes_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            force: false,
        };
        let first = install_skill(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/SKILL.md").is_file());
        let second = install_skill(args).unwrap();
        assert!(second.skipped);
    }

    #[test]
    fn install_instructions_writes_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            force: false,
        };
        let first = install_instructions(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/README.md").is_file());
        assert!(temp.path().join("kast/cli.md").is_file());
        assert!(temp.path().join("kast/rpc.md").is_file());
        assert!(temp.path().join("kast/lsp.md").is_file());
        let second = install_instructions(args).unwrap();
        assert!(second.skipped);
    }

    #[test]
    fn jetbrains_plugin_dirs_match_cask_profile_filter() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path();
        for dir in [
            "AndroidStudio2025.2",
            "AndroidStudio2026.2",
            "GoLand2024.2",
            "PyCharmCE2024.1",
            "AndroidStudio2025.2-backup/2025-07-27-00-54",
            "Toolbox",
            "AndroidStudio2025.2/plugins/python-ce/helpers/typeshed/stubs/flake8/flake8",
        ] {
            fs::create_dir_all(root.join(dir)).unwrap();
        }

        let dirs = jetbrains_plugin_dirs(root).unwrap();
        let relative: Vec<_> = dirs
            .iter()
            .map(|path| path.strip_prefix(root).unwrap().display().to_string())
            .collect();

        assert_eq!(
            relative,
            vec![
                "AndroidStudio2026.2/plugins",
                "AndroidStudio2025.2/plugins",
                "GoLand2024.2/plugins",
                "PyCharmCE2024.1/plugins",
            ]
        );
    }

    #[test]
    fn parses_homebrew_formula_tap() {
        let json = r#"{"formulae":[{"name":"kast","tap":"amichne/kast"}],"casks":[]}"#;
        assert_eq!(
            parse_homebrew_formula_tap(json).as_deref(),
            Some("amichne/kast")
        );
    }

    #[test]
    fn parses_homebrew_cask_metadata_version() {
        let json = r#"{"formulae":[],"casks":[{"token":"kast-plugin","version":"9.8.7"}]}"#;

        let metadata = parse_homebrew_cask_metadata(json).unwrap();

        assert_eq!(metadata.plugin_version, "9.8.7");
    }

    #[test]
    fn cask_name_uses_last_token_segment() {
        assert_eq!(cask_name("amichne/kast/kast-plugin"), "kast-plugin");
        assert_eq!(cask_name("kast-plugin"), "kast-plugin");
    }

    #[test]
    fn homebrew_formula_path_check_accepts_cellar_binary() {
        let prefix = Path::new("/opt/homebrew/Cellar/kast/0.7.16");
        let cli = Path::new("/opt/homebrew/Cellar/kast/0.7.16/bin/kast");

        assert!(path_is_below_homebrew_formula(cli, prefix));
        assert!(!path_is_below_homebrew_formula(
            Path::new("/Users/example/kast/target/release/kast"),
            prefix
        ));
    }
}

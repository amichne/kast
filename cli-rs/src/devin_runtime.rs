use crate::SCHEMA_VERSION;
use crate::cli::{
    BackendName, DevinRuntimeCommand, DevinRuntimePackageArgs, DevinRuntimePrefixArgs,
    DevinRuntimeVerifyArgs,
};
use crate::config::{
    self, BackendsConfig, CliConfig, HeadlessBackendConfig, PathsConfig, RuntimeConfig,
    ServerConfig, StandaloneBackendConfig,
};
use crate::error::{CliError, Result};
use crate::self_mgmt::{BackendComponentState, InstallState};
use flate2::Compression;
use flate2::write::GzEncoder;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

const PLATFORM: &str = "devin-headless-linux-x64";
const MANIFEST_KIND: &str = "KAST_DEVIN_HEADLESS_RUNTIME";

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum DevinRuntimeResult {
    Package(PackageResult),
    Setup(SetupResult),
    Verify(VerifyResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackageResult {
    pub version: String,
    pub platform: String,
    pub bundle_name: String,
    pub backend_install_name: String,
    pub output_path: String,
    pub checksum_path: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    pub prefix: String,
    pub config_path: String,
    pub backend_install_name: String,
    pub runtime_libs_dir: String,
    pub idea_home: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyResult {
    pub prefix: String,
    pub backend_name: String,
    pub workspace_root: String,
    pub doctor_ok: bool,
    pub up_backend_name: String,
    pub rpc_backend_name: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DevinRuntimeManifest {
    schema_version: u32,
    kind: String,
    version: String,
    platform: String,
    backend_install_name: String,
    config: ManifestConfig,
    build_commit: String,
    artifacts: Vec<ManifestArtifact>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManifestConfig {
    generated_by: String,
    path: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManifestArtifact {
    role: String,
    path: String,
    source_sha256: String,
}

#[derive(Debug, Serialize)]
struct BundleConfig {
    server: ServerConfig,
    runtime: RuntimeConfig,
    paths: PathsConfig,
    backends: BackendsConfig,
    cli: CliConfig,
    install: InstallState,
}

struct TempTree {
    path: PathBuf,
}

struct BundleLayout {
    prefix: PathBuf,
    manifest: DevinRuntimeManifest,
    backend_root: PathBuf,
    runtime_libs: PathBuf,
    idea_home: PathBuf,
    cli_path: PathBuf,
}

impl Drop for TempTree {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

pub fn run(command: DevinRuntimeCommand) -> Result<DevinRuntimeResult> {
    match command {
        DevinRuntimeCommand::Package(args) => package(args).map(DevinRuntimeResult::Package),
        DevinRuntimeCommand::Setup(args) => setup(args).map(DevinRuntimeResult::Setup),
        DevinRuntimeCommand::Verify(args) => verify(args).map(DevinRuntimeResult::Verify),
    }
}

fn package(args: DevinRuntimePackageArgs) -> Result<PackageResult> {
    let version = release_tag(&args.version);
    let normalized_version = normalized_version(&version);
    let bundle_name = format!("kast-devin-headless-runtime-linux-x64-{version}");
    let backend_install_name = format!("headless-{version}");
    let output_path = args.output.map(config::normalize).unwrap_or_else(|| {
        config::normalize(PathBuf::from("dist").join(format!("{bundle_name}.tar.gz")))
    });
    let checksum_path = checksum_path(&output_path);

    let cli_archive = require_input_file(args.cli_archive, "CLI archive")?;
    let backend_archive = require_input_file(args.backend_archive, "Backend archive")?;
    let temp = temp_tree("kast-devin-runtime-package")?;
    let cli_extract = temp.path.join("cli");
    let backend_extract = temp.path.join("backend");
    let staging_root = temp.path.join(&bundle_name);

    extract_zip_archive(&cli_archive, &cli_extract)?;
    extract_zip_archive(&backend_archive, &backend_extract)?;

    let cli_bin = cli_extract.join("kast");
    require_file(&cli_bin, "CLI archive must contain kast at its root")?;
    let backend_source = backend_extract.join("backend-headless");
    validate_backend_source(&backend_source, &version, &normalized_version)?;

    fs::create_dir_all(staging_root.join("bin"))?;
    fs::create_dir_all(staging_root.join("lib/backends"))?;
    fs::copy(&cli_bin, staging_root.join("bin/kast"))?;
    set_executable(&staging_root.join("bin/kast"))?;
    copy_dir(
        &backend_source,
        &staging_root
            .join("lib/backends")
            .join(&backend_install_name),
    )?;
    set_executable(
        &staging_root
            .join("lib/backends")
            .join(&backend_install_name)
            .join("kast-headless"),
    )?;
    write_setup_doc(&staging_root)?;
    copy_license_if_present(&staging_root)?;

    let manifest = DevinRuntimeManifest {
        schema_version: 1,
        kind: MANIFEST_KIND.to_string(),
        version: version.clone(),
        platform: PLATFORM.to_string(),
        backend_install_name: backend_install_name.clone(),
        config: ManifestConfig {
            generated_by: "kast devin-runtime setup".to_string(),
            path: "config.toml".to_string(),
        },
        build_commit: build_commit(),
        artifacts: vec![
            ManifestArtifact {
                role: "cli".to_string(),
                path: "bin/kast".to_string(),
                source_sha256: sha256_file(&cli_archive)?,
            },
            ManifestArtifact {
                role: "headless-backend".to_string(),
                path: format!("lib/backends/{backend_install_name}"),
                source_sha256: sha256_file(&backend_archive)?,
            },
        ],
    };
    write_json(&staging_root.join("manifest.json"), &manifest)?;

    if let Some(parent) = output_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp_output = temporary_output_path(&output_path);
    remove_file_if_exists(&temp_output)?;
    create_tar_gz(&temp_output, &temp.path, &bundle_name)?;
    remove_file_if_exists(&output_path)?;
    fs::rename(&temp_output, &output_path)?;
    let digest = sha256_file(&output_path)?;
    fs::write(
        &checksum_path,
        format!(
            "{}  {}\n",
            digest,
            output_path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("bundle.tar.gz")
        ),
    )?;

    Ok(PackageResult {
        version,
        platform: PLATFORM.to_string(),
        bundle_name,
        backend_install_name,
        output_path: output_path.display().to_string(),
        checksum_path: checksum_path.display().to_string(),
        schema_version: SCHEMA_VERSION,
    })
}

fn setup(args: DevinRuntimePrefixArgs) -> Result<SetupResult> {
    let layout = load_bundle_layout(args.prefix)?;
    validate_installed_bundle(&layout)?;
    fs::create_dir_all(layout.prefix.join("cache/daemons"))?;
    fs::create_dir_all(layout.prefix.join("logs"))?;

    let normalized_version = normalized_version(&layout.manifest.version);
    let config_payload = BundleConfig {
        server: ServerConfig {
            max_results: 500,
            request_timeout_millis: 30_000,
            max_concurrent_requests: 4,
        },
        runtime: RuntimeConfig {
            default_backend: Some(BackendName::Headless),
        },
        paths: PathsConfig {
            install_root: layout.prefix.clone(),
            bin_dir: layout.prefix.join("bin"),
            lib_dir: layout.prefix.join("lib"),
            cache_dir: layout.prefix.join("cache"),
            logs_dir: layout.prefix.join("logs"),
            descriptor_dir: layout.prefix.join("cache/daemons"),
            socket_dir: env::temp_dir(),
        },
        backends: BackendsConfig {
            standalone: StandaloneBackendConfig {
                runtime_libs_dir: None,
            },
            headless: HeadlessBackendConfig {
                runtime_libs_dir: Some(layout.runtime_libs.clone()),
                idea_home: Some(layout.idea_home.clone()),
            },
        },
        cli: CliConfig {
            binary_path: layout.cli_path.clone(),
        },
        install: InstallState {
            version: normalized_version.clone(),
            backend_version: normalized_version.clone(),
            installed_at: format!("{}:{}", PLATFORM, layout.manifest.version),
            platform: PLATFORM.to_string(),
            components: vec![
                "cli".to_string(),
                "headless-backend".to_string(),
                "config".to_string(),
            ],
            backends: vec![BackendComponentState {
                name: "headless".to_string(),
                version: normalized_version,
                install_dir: layout.backend_root.display().to_string(),
                runtime_libs_dir: layout.runtime_libs.display().to_string(),
                idea_home: Some(layout.idea_home.display().to_string()),
            }],
            managed_paths: vec![
                "bin".to_string(),
                "lib".to_string(),
                "cache".to_string(),
                "logs".to_string(),
                "config.toml".to_string(),
            ],
            shell_rc_patches: vec![],
            repos: vec![],
            schema_version: 6,
        },
    };
    let config_file = layout.prefix.join("config.toml");
    fs::write(&config_file, toml::to_string_pretty(&config_payload)?)?;

    Ok(SetupResult {
        prefix: layout.prefix.display().to_string(),
        config_path: config_file.display().to_string(),
        backend_install_name: layout.manifest.backend_install_name,
        runtime_libs_dir: layout.runtime_libs.display().to_string(),
        idea_home: layout.idea_home.display().to_string(),
        schema_version: SCHEMA_VERSION,
    })
}

fn verify(args: DevinRuntimeVerifyArgs) -> Result<VerifyResult> {
    let prefix = resolve_prefix(args.prefix)?;
    let layout = read_bundle_layout(prefix)?;
    validate_installed_bundle(&layout)?;
    let config_file = layout.prefix.join("config.toml");
    require_file(
        &config_file,
        "Missing config.toml; run `kast devin-runtime setup` first",
    )?;
    assert_config_points_at_bundle(&config_file, &layout)?;

    let doctor = run_bundle_cli(&layout, ["doctor"])?;
    let doctor_json: serde_json::Value = serde_json::from_str(&doctor).map_err(|error| {
        CliError::new(
            "DEVIN_RUNTIME_VERIFY_FAILED",
            format!("kast doctor did not return JSON: {error}"),
        )
    })?;
    if doctor_json.get("ok").and_then(|value| value.as_bool()) != Some(true) {
        return Err(CliError::new(
            "DEVIN_RUNTIME_VERIFY_FAILED",
            format!("kast doctor did not report ok=true: {doctor_json}"),
        ));
    }

    let temp_workspace = if args.workspace_root.is_none() {
        Some(temp_tree("kast-devin-runtime-verify")?)
    } else {
        None
    };
    let workspace_root = match args.workspace_root {
        Some(path) => config::normalize(path),
        None => temp_workspace
            .as_ref()
            .expect("temp workspace")
            .path
            .join("workspace"),
    };
    fs::create_dir_all(&workspace_root)?;

    let up = run_bundle_cli(
        &layout,
        [
            "up",
            "--workspace-root",
            workspace_root.to_str().unwrap_or_default(),
            "--accept-indexing=true",
        ],
    )?;
    let up_backend_name = match parse_up_backend_name(&up) {
        Ok(backend_name) => backend_name,
        Err(error) => {
            let _ = run_bundle_cli(
                &layout,
                [
                    "stop",
                    "--workspace-root",
                    workspace_root.to_str().unwrap_or_default(),
                ],
            );
            return Err(error);
        }
    };
    if up_backend_name != "headless" {
        let _ = run_bundle_cli(
            &layout,
            [
                "stop",
                "--workspace-root",
                workspace_root.to_str().unwrap_or_default(),
            ],
        );
        return Err(CliError::new(
            "DEVIN_RUNTIME_VERIFY_FAILED",
            format!("kast up did not select headless backend: {up}"),
        ));
    }

    let rpc_result = run_bundle_cli(
        &layout,
        [
            "rpc",
            r#"{"jsonrpc":"2.0","method":"runtime/status","id":1}"#,
            "--workspace-root",
            workspace_root.to_str().unwrap_or_default(),
        ],
    );
    let _ = run_bundle_cli(
        &layout,
        [
            "stop",
            "--workspace-root",
            workspace_root.to_str().unwrap_or_default(),
        ],
    );

    let rpc = rpc_result?;
    let rpc_backend_name = parse_rpc_backend_name(&rpc)?;
    if rpc_backend_name != "headless" {
        return Err(CliError::new(
            "DEVIN_RUNTIME_VERIFY_FAILED",
            format!("kast rpc runtime/status did not use headless backend: {rpc}"),
        ));
    }

    Ok(VerifyResult {
        prefix: layout.prefix.display().to_string(),
        backend_name: "headless".to_string(),
        workspace_root: workspace_root.display().to_string(),
        doctor_ok: true,
        up_backend_name,
        rpc_backend_name,
        schema_version: SCHEMA_VERSION,
    })
}

fn load_bundle_layout(prefix: Option<PathBuf>) -> Result<BundleLayout> {
    read_bundle_layout(resolve_prefix(prefix)?)
}

fn read_bundle_layout(prefix: PathBuf) -> Result<BundleLayout> {
    let prefix = existing_dir(prefix, "Bundle prefix")?;
    let manifest: DevinRuntimeManifest = serde_json::from_str(&fs::read_to_string(
        prefix.join("manifest.json"),
    )?)
    .map_err(|error| {
        CliError::new(
            "DEVIN_RUNTIME_MANIFEST_INVALID",
            format!("Invalid manifest.json: {error}"),
        )
    })?;
    if manifest.kind != MANIFEST_KIND {
        return Err(CliError::new(
            "DEVIN_RUNTIME_MANIFEST_INVALID",
            format!("unexpected bundle kind: {}", manifest.kind),
        ));
    }
    if manifest.platform != PLATFORM {
        return Err(CliError::new(
            "DEVIN_RUNTIME_MANIFEST_INVALID",
            format!("unexpected platform: {}", manifest.platform),
        ));
    }
    if manifest.backend_install_name.trim().is_empty() {
        return Err(CliError::new(
            "DEVIN_RUNTIME_MANIFEST_INVALID",
            "manifest is missing backendInstallName",
        ));
    }
    let backend_root = prefix
        .join("lib/backends")
        .join(&manifest.backend_install_name);
    Ok(BundleLayout {
        runtime_libs: backend_root.join("runtime-libs"),
        idea_home: backend_root.join("idea-home"),
        cli_path: prefix.join("bin/kast"),
        prefix,
        manifest,
        backend_root,
    })
}

fn validate_installed_bundle(layout: &BundleLayout) -> Result<()> {
    require_file(&layout.cli_path, "Missing executable CLI")?;
    require_file(
        &layout.backend_root.join("kast-headless"),
        "Missing executable headless launcher",
    )?;
    require_file(
        &layout.runtime_libs.join("classpath.txt"),
        "Missing runtime classpath",
    )?;
    require_file(
        &layout.idea_home.join("lib/nio-fs.jar"),
        "Missing IDEA nio-fs.jar",
    )?;
    require_file(
        &layout.idea_home.join("modules/module-descriptors.dat"),
        "Missing IDEA module descriptors",
    )?;
    if !layout.idea_home.join("plugins/kast-headless").is_dir() {
        return Err(CliError::new(
            "DEVIN_RUNTIME_INVALID",
            "Missing bundled kast-headless plugin",
        ));
    }
    reject_fat_jars(&layout.backend_root, "Devin headless runtime")?;
    Ok(())
}

fn validate_backend_source(
    source_root: &Path,
    version: &str,
    normalized_version: &str,
) -> Result<()> {
    if !source_root.is_dir() {
        return Err(CliError::new(
            "DEVIN_RUNTIME_ARCHIVE_INVALID",
            "Backend archive must contain backend-headless/",
        ));
    }
    require_file(
        &source_root.join("kast-headless"),
        "Backend archive missing kast-headless launcher",
    )?;
    require_file(
        &source_root.join("runtime-libs/classpath.txt"),
        "Backend archive missing runtime-libs/classpath.txt",
    )?;
    require_file(
        &source_root.join("idea-home/lib/nio-fs.jar"),
        "Backend archive missing idea-home/lib/nio-fs.jar",
    )?;
    require_file(
        &source_root.join("idea-home/modules/module-descriptors.dat"),
        "Backend archive missing idea-home/modules/module-descriptors.dat",
    )?;
    if !source_root.join("idea-home/plugins/kast-headless").is_dir() {
        return Err(CliError::new(
            "DEVIN_RUNTIME_ARCHIVE_INVALID",
            "Backend archive missing bundled kast-headless plugin",
        ));
    }

    let expected_launcher_jar = format!("backend-headless-{normalized_version}-launcher.jar");
    let expected_plugin_jar = format!("backend-headless-{normalized_version}-plugin.jar");
    let classpath = fs::read_to_string(source_root.join("runtime-libs/classpath.txt"))?;
    if !classpath
        .lines()
        .any(|line| line.trim() == expected_launcher_jar)
    {
        return Err(CliError::new(
            "DEVIN_RUNTIME_ARCHIVE_VERSION_MISMATCH",
            format!(
                "Backend archive does not match requested version {version}: runtime-libs/classpath.txt must contain {expected_launcher_jar}"
            ),
        ));
    }
    require_file(
        &source_root
            .join("runtime-libs")
            .join(&expected_launcher_jar),
        &format!(
            "Backend archive does not match requested version {version}: missing runtime-libs/{expected_launcher_jar}"
        ),
    )?;
    require_file(
        &source_root
            .join("idea-home/plugins/kast-headless/lib")
            .join(&expected_plugin_jar),
        &format!(
            "Backend archive does not match requested version {version}: missing idea-home/plugins/kast-headless/lib/{expected_plugin_jar}"
        ),
    )?;
    reject_fat_jars(source_root, "Devin headless backend archive")?;
    Ok(())
}

fn assert_config_points_at_bundle(config_file: &Path, layout: &BundleLayout) -> Result<()> {
    let config = fs::read_to_string(config_file)?;
    let required = [
        "[runtime]",
        "defaultBackend = \"headless\"",
        "[backends.headless]",
        &format!("runtimeLibsDir = \"{}\"", layout.runtime_libs.display()),
        &format!("ideaHome = \"{}\"", layout.idea_home.display()),
        &format!("binaryPath = \"{}\"", layout.cli_path.display()),
    ];
    for expected in required {
        if !config.contains(expected) {
            return Err(CliError::new(
                "DEVIN_RUNTIME_VERIFY_FAILED",
                format!("config.toml does not contain expected entry: {expected}"),
            ));
        }
    }
    Ok(())
}

fn run_bundle_cli<const N: usize>(layout: &BundleLayout, args: [&str; N]) -> Result<String> {
    let output = Command::new(&layout.cli_path)
        .args(args)
        .env("KAST_CONFIG_HOME", &layout.prefix)
        .output()
        .map_err(|error| {
            CliError::new(
                "DEVIN_RUNTIME_VERIFY_FAILED",
                format!("Failed to run bundled kast: {error}"),
            )
        })?;
    if !output.status.success() {
        return Err(CliError::new(
            "DEVIN_RUNTIME_VERIFY_FAILED",
            format!(
                "Bundled kast command failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            ),
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn parse_up_backend_name(raw: &str) -> Result<String> {
    let payload: serde_json::Value = serde_json::from_str(raw)?;
    let selected = payload.get("selected").unwrap_or(&serde_json::Value::Null);
    let runtime_status = selected
        .get("runtimeStatus")
        .unwrap_or(&serde_json::Value::Null);
    let descriptor = selected
        .get("descriptor")
        .unwrap_or(&serde_json::Value::Null);
    runtime_status
        .get("backendName")
        .or_else(|| descriptor.get("backendName"))
        .and_then(|value| value.as_str())
        .map(ToString::to_string)
        .ok_or_else(|| {
            CliError::new(
                "DEVIN_RUNTIME_VERIFY_FAILED",
                format!("kast up did not report a backend name: {raw}"),
            )
        })
}

fn parse_rpc_backend_name(raw: &str) -> Result<String> {
    let payload: serde_json::Value = serde_json::from_str(raw)?;
    payload
        .get("result")
        .and_then(|result| result.get("backendName"))
        .and_then(|value| value.as_str())
        .map(ToString::to_string)
        .ok_or_else(|| {
            CliError::new(
                "DEVIN_RUNTIME_VERIFY_FAILED",
                format!("kast rpc runtime/status did not report a backend name: {raw}"),
            )
        })
}

fn resolve_prefix(prefix: Option<PathBuf>) -> Result<PathBuf> {
    match prefix {
        Some(path) => Ok(config::normalize(path)),
        None => Ok(default_prefix()),
    }
}

fn default_prefix() -> PathBuf {
    if let Ok(exe) = env::current_exe()
        && let Some(parent) = exe.parent()
        && parent.file_name().and_then(|name| name.to_str()) == Some("bin")
        && let Some(prefix) = parent.parent()
    {
        return prefix.to_path_buf();
    }
    env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

fn temp_tree(prefix: &str) -> Result<TempTree> {
    let unique = format!("{}.{}.{}", prefix, std::process::id(), timestamp_nanos());
    let path = env::temp_dir().join(unique);
    fs::create_dir_all(&path)?;
    Ok(TempTree { path })
}

fn timestamp_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default()
}

fn require_input_file(path: PathBuf, label: &str) -> Result<PathBuf> {
    let path = config::normalize(path);
    if path.is_file() {
        return Ok(path);
    }
    Err(CliError::new(
        "DEVIN_RUNTIME_INPUT_NOT_FOUND",
        format!("{label} not found: {}", path.display()),
    ))
}

fn existing_dir(path: PathBuf, label: &str) -> Result<PathBuf> {
    let path = config::normalize(path);
    if path.is_dir() {
        return Ok(path);
    }
    Err(CliError::new(
        "DEVIN_RUNTIME_INPUT_NOT_FOUND",
        format!("{label} not found: {}", path.display()),
    ))
}

fn require_file(path: &Path, message: &str) -> Result<()> {
    if path.is_file() {
        return Ok(());
    }
    Err(CliError::new(
        "DEVIN_RUNTIME_INVALID",
        format!("{message}: {}", path.display()),
    ))
}

fn reject_fat_jars(root: &Path, label: &str) -> Result<()> {
    let libs = root.join("libs");
    if !libs.is_dir() {
        return Ok(());
    }
    let mut stack = vec![libs];
    while let Some(dir) = stack.pop() {
        for entry in fs::read_dir(dir)? {
            let path = entry?.path();
            if path.is_dir() {
                stack.push(path);
            } else if path
                .file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with("-all.jar"))
            {
                return Err(CliError::new(
                    "DEVIN_RUNTIME_FAT_JAR",
                    format!("{label} must not contain fat jars: {}", path.display()),
                ));
            }
        }
    }
    Ok(())
}

fn extract_zip_archive(archive: &Path, output_dir: &Path) -> Result<()> {
    let file = fs::File::open(archive)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "DEVIN_RUNTIME_ARCHIVE_INVALID",
            format!("Invalid zip archive: {error}"),
        )
    })?;
    fs::create_dir_all(output_dir)?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|error| {
            CliError::new(
                "DEVIN_RUNTIME_ARCHIVE_INVALID",
                format!("Invalid zip entry: {error}"),
            )
        })?;
        let Some(enclosed_name) = entry.enclosed_name() else {
            return Err(CliError::new(
                "DEVIN_RUNTIME_ARCHIVE_INVALID",
                format!("Unsafe zip member: {}", entry.name()),
            ));
        };
        let target = output_dir.join(enclosed_name);
        if entry.is_dir() {
            fs::create_dir_all(&target)?;
            continue;
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut output = fs::File::create(&target)?;
        io::copy(&mut entry, &mut output)?;
        #[cfg(unix)]
        if let Some(mode) = entry.unix_mode() {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&target, fs::Permissions::from_mode(mode))?;
        }
    }
    Ok(())
}

fn copy_dir(source: &Path, destination: &Path) -> Result<()> {
    fs::create_dir_all(destination)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            copy_dir(&source_path, &destination_path)?;
        } else if file_type.is_file() {
            if let Some(parent) = destination_path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(&source_path, &destination_path)?;
            let permissions = fs::metadata(&source_path)?.permissions();
            fs::set_permissions(&destination_path, permissions)?;
        }
    }
    Ok(())
}

fn write_setup_doc(staging_root: &Path) -> Result<()> {
    fs::write(
        staging_root.join("SETUP.md"),
        "# Kast Devin Headless Runtime\n\n\
Run setup after unpacking the archive:\n\n\
```bash\n\
bin/kast devin-runtime setup --prefix \"$PWD\"\n\
bin/kast devin-runtime verify --prefix \"$PWD\"\n\
```\n\n\
The setup command writes `config.toml` with absolute paths for the unpacked prefix.\n",
    )?;
    Ok(())
}

fn copy_license_if_present(staging_root: &Path) -> Result<()> {
    let license = Path::new("LICENSE");
    if license.is_file() {
        fs::copy(license, staging_root.join("LICENSE"))?;
    }
    Ok(())
}

fn write_json<T: Serialize>(path: &Path, payload: &T) -> Result<()> {
    let mut content = serde_json::to_string_pretty(payload)?;
    content.push('\n');
    fs::write(path, content)?;
    Ok(())
}

fn create_tar_gz(output: &Path, parent: &Path, bundle_name: &str) -> Result<()> {
    let file = fs::File::create(output)?;
    let encoder = GzEncoder::new(file, Compression::default());
    let mut builder = tar::Builder::new(encoder);
    builder.append_dir_all(bundle_name, parent.join(bundle_name))?;
    let encoder = builder.into_inner()?;
    encoder.finish()?;
    Ok(())
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut hasher = Sha256::new();
    let mut file = fs::File::open(path)?;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn checksum_path(output_path: &Path) -> PathBuf {
    PathBuf::from(format!("{}.sha256", output_path.display()))
}

fn temporary_output_path(output_path: &Path) -> PathBuf {
    let file_name = output_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kast-devin-runtime.tar.gz");
    output_path.with_file_name(format!(".{file_name}.tmp"))
}

fn remove_file_if_exists(path: &Path) -> Result<()> {
    if path.is_file() {
        fs::remove_file(path)?;
    }
    Ok(())
}

fn set_executable(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

fn release_tag(value: &str) -> String {
    let trimmed = value.trim();
    if trimmed.starts_with('v') {
        trimmed.to_string()
    } else {
        format!("v{trimmed}")
    }
}

fn normalized_version(value: &str) -> String {
    value.trim_start_matches('v').to_string()
}

fn build_commit() -> String {
    env::var("KAST_BUILD_COMMIT")
        .or_else(|_| env::var("GITHUB_SHA"))
        .unwrap_or_else(|_| "unknown".to_string())
}

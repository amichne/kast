use crate::SCHEMA_VERSION;
use crate::cli;
use crate::error::{CliError, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::env;
use std::fmt;
use std::fs;
use std::fs::OpenOptions;
use std::io::Read;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const INSTALL_MANIFEST_FILE: &str = "install.json";
const TOOL_NAME: &str = "kast";
const DEFAULT_PROFILE: &str = "user-local";

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct KastInstallManifest {
    #[serde(default = "tool_name")]
    pub tool: String,
    #[serde(default)]
    pub install_id: String,
    #[serde(default = "default_profile")]
    pub profile: String,
    #[serde(default)]
    pub active_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_version: Option<String>,
    #[serde(default)]
    pub created_at: String,
    #[serde(default)]
    pub updated_at: String,
    pub roots: ManifestRoots,
    pub entrypoints: ManifestEntrypoints,
    #[serde(default)]
    pub schemas: ManifestSchemas,
    #[serde(default)]
    pub version: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub backend_version: String,
    #[serde(default)]
    pub installed_at: String,
    #[serde(default)]
    pub platform: String,
    #[serde(default)]
    pub components: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub backends: Vec<BackendComponentState>,
    #[serde(default)]
    pub managed_paths: Vec<String>,
    #[serde(default)]
    pub owned_paths: Vec<String>,
    #[serde(default)]
    pub shell_rc_patches: Vec<Value>,
    #[serde(default)]
    pub repos: Vec<ManagedRepo>,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BackendComponentState {
    pub name: String,
    pub version: String,
    pub install_dir: String,
    pub runtime_libs_dir: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idea_home: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManagedRepo {
    pub path: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub copilot_package_version: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub resources: Vec<ManagedRepoResource>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManagedRepoResource {
    pub kind: ManagedResourceKind,
    pub target_path: String,
    pub primitive_version: String,
    pub source_bundle_sha256: String,
    pub output_paths: Vec<String>,
    pub output_checksums: Vec<ManagedResourceOutputChecksum>,
    pub installed_at: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub history: Vec<ManagedRepoResourceHistory>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManagedResourceOutputChecksum {
    pub path: String,
    pub sha256: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region: Option<ManagedResourceChecksumRegion>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ManagedResourceChecksumRegion {
    KastManagedFence,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManagedRepoResourceHistory {
    pub primitive_version: String,
    pub source_bundle_sha256: String,
    pub installed_at: String,
    pub output_checksums: Vec<ManagedResourceOutputChecksum>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ManagedResourceKind {
    CopilotPackage,
    Skill,
    Instructions,
    AgentGuidance,
}

impl fmt::Display for ManagedResourceKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::CopilotPackage => "COPILOT_PACKAGE",
            Self::Skill => "SKILL",
            Self::Instructions => "INSTRUCTIONS",
            Self::AgentGuidance => "AGENT_GUIDANCE",
        })
    }
}

#[derive(Debug, Clone)]
pub struct ManagedResourceVerification {
    pub ok: bool,
    pub issues: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestRoots {
    pub install: String,
    pub bin: String,
    pub config: String,
    pub data: String,
    pub cache: String,
    pub runtime: String,
    pub logs: String,
    pub locks: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntrypoints {
    pub shim: String,
    pub active_binary: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestSchemas {
    pub manifest: u32,
    pub workspace_registry: u32,
    pub symbol_index: u32,
}

impl Default for ManifestSchemas {
    fn default() -> Self {
        Self {
            manifest: 1,
            workspace_registry: 1,
            symbol_index: 3,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ResolvedKastPaths {
    pub install_root: PathBuf,
    pub manifest_file: PathBuf,
    pub bin_dir: PathBuf,
    pub lib_dir: PathBuf,
    pub data_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub locks_dir: PathBuf,
    pub descriptor_dir: PathBuf,
    pub socket_dir: PathBuf,
    pub config_root: PathBuf,
    pub config_file: PathBuf,
    pub shim_path: PathBuf,
    pub active_binary: PathBuf,
    pub headless_runtime_libs_dir: PathBuf,
    pub headless_idea_home: Option<PathBuf>,
}

pub fn resolve_paths() -> Result<ResolvedKastPaths> {
    if let Some(receipt) = crate::local_development::active_local_development_receipt()? {
        return paths_from_manifest(&read_manifest_at(&receipt.install_manifest)?);
    }
    #[cfg(target_os = "macos")]
    match crate::install::resolve_macos_homebrew_authority() {
        crate::install::MacosHomebrewAuthorityResolution::Active(receipt)
        | crate::install::MacosHomebrewAuthorityResolution::Recoverable(receipt) => {
            let mut paths = default_resolved_paths();
            let bin_dir = receipt.cli.binary.parent().ok_or_else(|| {
                CliError::new(
                    "MACOS_HOMEBREW_RECEIPT_INVALID",
                    format!(
                        "macOS Homebrew receipt CLI has no parent directory: {}",
                        receipt.cli.binary.display()
                    ),
                )
            })?;
            paths.bin_dir = bin_dir.to_path_buf();
            paths.shim_path = receipt.cli.binary.clone();
            paths.active_binary = receipt.cli.binary;
            return Ok(paths);
        }
        crate::install::MacosHomebrewAuthorityResolution::Blocked(error) => return Err(error),
        crate::install::MacosHomebrewAuthorityResolution::Absent => {}
    }
    let manifest_path = default_install_manifest_path();
    if manifest_path.is_file() {
        return paths_from_manifest(&read_manifest_at(&manifest_path)?);
    }
    Ok(default_resolved_paths())
}

pub fn default_resolved_paths() -> ResolvedKastPaths {
    let install_root = default_install_root();
    let config_root = default_config_root();
    let bin_dir = home_dir().join(".local/bin");
    let current = install_root.join("current");
    let lib_dir = current.join("lib");
    let cache_dir = env_path("KAST_CACHE_HOME").unwrap_or_else(|| home_dir().join(".cache/kast"));
    let data_dir = install_root.join("state");
    let runtime_dir = install_root.join("runtime");
    let logs_dir = home_dir().join(".local/state/kast/logs");
    let locks_dir = install_root.join("locks");
    ResolvedKastPaths {
        install_root: install_root.clone(),
        manifest_file: install_root.join(INSTALL_MANIFEST_FILE),
        bin_dir: bin_dir.clone(),
        lib_dir: lib_dir.clone(),
        data_dir,
        cache_dir,
        logs_dir,
        runtime_dir: runtime_dir.clone(),
        locks_dir,
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: bin_dir.join("kast"),
        active_binary: current.join("bin/kast"),
        headless_runtime_libs_dir: lib_dir.join("backends/headless/current/runtime-libs"),
        headless_idea_home: None,
    }
}

pub fn default_install_root() -> PathBuf {
    env_path("KAST_INSTALL_ROOT").unwrap_or_else(|| home_dir().join(".local/share/kast"))
}

pub fn default_install_manifest_path() -> PathBuf {
    default_install_root().join(INSTALL_MANIFEST_FILE)
}

pub fn default_config_root() -> PathBuf {
    env_path("KAST_CONFIG_HOME").unwrap_or_else(|| home_dir().join(".config/kast"))
}

pub fn read_install_manifest() -> Result<Option<KastInstallManifest>> {
    let path = default_install_manifest_path();
    if !path.is_file() {
        return Ok(None);
    }
    read_manifest_at(&path).map(Some)
}

pub fn write_install_manifest(manifest: &KastInstallManifest) -> Result<PathBuf> {
    let paths = paths_from_manifest(manifest)?;
    with_install_lock(&paths, || {
        write_manifest_atomic(&paths.manifest_file, manifest)
    })?;
    Ok(paths.manifest_file)
}

pub fn fresh_manifest() -> KastInstallManifest {
    let paths = default_resolved_paths();
    manifest_from_paths(paths, None, vec!["cli".to_string(), "config".to_string()])
}

pub fn manifest_from_paths(
    paths: ResolvedKastPaths,
    previous_version: Option<String>,
    components: Vec<String>,
) -> KastInstallManifest {
    let now = current_timestamp();
    let version = cli::version().to_string();
    KastInstallManifest {
        tool: TOOL_NAME.to_string(),
        install_id: uuid::Uuid::new_v4().to_string(),
        profile: DEFAULT_PROFILE.to_string(),
        active_version: version.clone(),
        previous_version,
        created_at: now.clone(),
        updated_at: now.clone(),
        roots: ManifestRoots {
            install: paths.install_root.display().to_string(),
            bin: paths.bin_dir.display().to_string(),
            config: paths.config_root.display().to_string(),
            data: paths.data_dir.display().to_string(),
            cache: paths.cache_dir.display().to_string(),
            runtime: paths.runtime_dir.display().to_string(),
            logs: paths.logs_dir.display().to_string(),
            locks: paths.locks_dir.display().to_string(),
        },
        entrypoints: ManifestEntrypoints {
            shim: paths.shim_path.display().to_string(),
            active_binary: paths.active_binary.display().to_string(),
        },
        schemas: ManifestSchemas::default(),
        version,
        backend_version: String::new(),
        installed_at: now,
        platform: format!("{}-{}", env::consts::OS, env::consts::ARCH),
        components,
        backends: vec![],
        managed_paths: vec![],
        owned_paths: owned_paths(&paths),
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    }
}

pub fn paths_from_manifest(manifest: &KastInstallManifest) -> Result<ResolvedKastPaths> {
    if manifest.tool != TOOL_NAME {
        return Err(CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!(
                "Install manifest tool must be `{TOOL_NAME}`, got `{}`.",
                manifest.tool
            ),
        ));
    }
    let install_root = normalize(PathBuf::from(&manifest.roots.install));
    let config_root = normalize(PathBuf::from(&manifest.roots.config));
    let runtime_dir = normalize(PathBuf::from(&manifest.roots.runtime));
    let lib_dir = install_root.join("current/lib");
    let headless = manifest
        .backends
        .iter()
        .find(|backend| backend.name == "headless");
    Ok(ResolvedKastPaths {
        install_root: install_root.clone(),
        manifest_file: install_root.join(INSTALL_MANIFEST_FILE),
        bin_dir: normalize(PathBuf::from(&manifest.roots.bin)),
        lib_dir: lib_dir.clone(),
        data_dir: normalize(PathBuf::from(&manifest.roots.data)),
        cache_dir: normalize(PathBuf::from(&manifest.roots.cache)),
        logs_dir: normalize(PathBuf::from(&manifest.roots.logs)),
        locks_dir: normalize(PathBuf::from(&manifest.roots.locks)),
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir.clone(),
        runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: normalize(PathBuf::from(&manifest.entrypoints.shim)),
        active_binary: normalize(PathBuf::from(&manifest.entrypoints.active_binary)),
        headless_runtime_libs_dir: headless
            .map(|backend| normalize(PathBuf::from(&backend.runtime_libs_dir)))
            .unwrap_or_else(|| lib_dir.join("backends/headless/current/runtime-libs")),
        headless_idea_home: headless
            .and_then(|backend| backend.idea_home.as_ref())
            .map(|path| normalize(PathBuf::from(path))),
    })
}

pub fn ensure_install_directories(paths: &ResolvedKastPaths) -> Result<()> {
    for directory in [
        &paths.install_root,
        &paths.bin_dir,
        &paths.data_dir,
        &paths.cache_dir,
        &paths.runtime_dir,
        &paths.logs_dir,
        &paths.locks_dir,
        &paths.config_root,
        &paths.install_root.join("versions"),
    ] {
        fs::create_dir_all(directory)?;
    }
    Ok(())
}

pub fn install_current_executable() -> Result<KastInstallManifest> {
    let mut paths = default_resolved_paths();
    let previous = read_install_manifest()?.map(|manifest| manifest.active_version);
    let mut manifest = manifest_from_paths(
        paths.clone(),
        previous,
        vec!["cli".to_string(), "config".to_string()],
    );
    let version_dir = paths.install_root.join("versions").join(cli::version());
    paths.active_binary = version_dir.join("bin/kast");
    manifest.entrypoints.active_binary = paths.active_binary.display().to_string();
    manifest.owned_paths = owned_paths(&paths);

    with_install_lock(&paths, || {
        ensure_install_directories(&paths)?;
        let staged = paths
            .install_root
            .join("versions")
            .join(format!("{}.tmp", cli::version()));
        remove_path(&staged)?;
        fs::create_dir_all(staged.join("bin"))?;
        fs::copy(env::current_exe()?, staged.join("bin/kast"))?;
        make_executable(&staged.join("bin/kast"))?;
        remove_path(&version_dir)?;
        fs::rename(&staged, &version_dir)?;
        replace_symlink_or_copy(&version_dir, &paths.install_root.join("current"))?;
        if let Some(previous) = &manifest.previous_version {
            let previous_dir = paths.install_root.join("versions").join(previous);
            if previous_dir.exists() {
                replace_symlink_or_copy(&previous_dir, &paths.install_root.join("previous"))?;
            }
        }
        write_shim(&paths.shim_path, &paths.active_binary)?;
        write_manifest_atomic(&paths.manifest_file, &manifest)?;
        Ok(())
    })?;
    Ok(manifest)
}

fn read_manifest_at(path: &Path) -> Result<KastInstallManifest> {
    serde_json::from_str(&fs::read_to_string(path)?).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!(
                "Invalid Kast install manifest at {}: {error}",
                path.display()
            ),
        )
    })
}

pub(crate) fn write_manifest_atomic(path: &Path, manifest: &KastInstallManifest) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension(format!("json.tmp-{}", std::process::id()));
    let mut file = fs::File::create(&temp)?;
    file.write_all(serde_json::to_vec_pretty(manifest)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temp, path)?;
    Ok(())
}

pub(crate) fn with_install_lock<T>(
    paths: &ResolvedKastPaths,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    fs::create_dir_all(&paths.locks_dir)?;
    let lock_path = paths.locks_dir.join("install.lock");
    let lock_file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(lock_path)?;
    lock_exclusive(&lock_file)?;
    let result = action();
    unlock(&lock_file)?;
    result
}

#[cfg(unix)]
fn lock_exclusive(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(unix)]
fn unlock(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_UN) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn lock_exclusive(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn unlock(_file: &fs::File) -> Result<()> {
    Ok(())
}

pub(crate) fn write_shim(shim_path: &Path, active_binary: &Path) -> Result<()> {
    if let Some(parent) = shim_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let content = render_shim(active_binary);
    fs::write(shim_path, content)?;
    make_executable(shim_path)
}

pub(crate) fn is_managed_shim_for(shim_path: &Path, active_binary: &Path) -> bool {
    if fs::read_link(shim_path)
        .ok()
        .is_some_and(|target| normalize(target) == normalize(active_binary.to_path_buf()))
    {
        return true;
    }
    fs::read_to_string(shim_path).is_ok_and(|content| content == render_shim(active_binary))
}

fn render_shim(active_binary: &Path) -> String {
    format!(
        "#!/usr/bin/env bash\nset -euo pipefail\nexec {} \"$@\"\n",
        shell_quote(&active_binary.display().to_string())
    )
}

#[cfg(unix)]
pub(crate) fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
pub(crate) fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

pub(crate) fn replace_symlink_or_copy(target: &Path, link: &Path) -> Result<()> {
    remove_path(link)?;
    if let Some(parent) = link.parent() {
        fs::create_dir_all(parent)?;
    }
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, link)?;
        Ok(())
    }
    #[cfg(not(unix))]
    {
        copy_dir(target, link)
    }
}

pub(crate) fn remove_path(path: &Path) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        fs::remove_dir_all(path)?;
    } else {
        fs::remove_file(path)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn copy_dir(source: &Path, target: &Path) -> Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&source_path, &target_path)?;
        } else {
            fs::copy(&source_path, &target_path)?;
        }
    }
    Ok(())
}

pub(crate) fn owned_paths(paths: &ResolvedKastPaths) -> Vec<String> {
    vec![
        paths.shim_path.clone(),
        paths.install_root.join("current"),
        paths.install_root.join("previous"),
        paths.install_root.join("versions"),
        paths.runtime_dir.clone(),
        paths.locks_dir.clone(),
    ]
    .into_iter()
    .map(|path| path.display().to_string())
    .collect()
}

pub(crate) fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}

pub(crate) fn sha256_bytes(bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(bytes);
    hex::encode(digest.finalize())
}

pub(crate) fn sha256_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 1024 * 64];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(hex::encode(digest.finalize()))
}

pub(crate) fn kast_managed_fence_sha256(path: &Path) -> Result<String> {
    let content = fs::read_to_string(path)?;
    let region = extract_kast_managed_fence(&content).ok_or_else(|| {
        CliError::new(
            "INSTALL_MANAGED_REGION_MISSING",
            format!("Kast managed fence was not found in {}", path.display()),
        )
    })?;
    Ok(sha256_bytes(region.as_bytes()))
}

fn extract_kast_managed_fence(content: &str) -> Option<&str> {
    const START: &str = "<kast>";
    const ATTRIBUTE_START: &str =
        r#"<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">"#;
    const END: &str = "</kast>";
    const LEGACY_START: &str = "<!-- BEGIN KAST MANAGED -->";
    const LEGACY_END: &str = "<!-- END KAST MANAGED -->";
    extract_kast_managed_fence_with_markers(content, START, END)
        .or_else(|| extract_kast_managed_fence_with_markers(content, ATTRIBUTE_START, END))
        .or_else(|| extract_kast_managed_fence_with_markers(content, LEGACY_START, LEGACY_END))
}

fn extract_kast_managed_fence_with_markers<'a>(
    content: &'a str,
    start_marker: &str,
    end_marker: &str,
) -> Option<&'a str> {
    let start = content.find(start_marker)?;
    let after_start = start + start_marker.len();
    let relative_end = content[after_start..].find(end_marker)?;
    let end = after_start + relative_end + end_marker.len();
    Some(&content[start..end])
}

pub fn verify_managed_resource_outputs(
    resource: &ManagedRepoResource,
) -> Result<ManagedResourceVerification> {
    let mut issues = Vec::new();
    for output in &resource.output_checksums {
        let path = Path::new(&output.path);
        if !path.is_file() {
            issues.push(format!(
                "{} output is missing: {}",
                resource.kind,
                path.display()
            ));
            continue;
        }
        let actual = match output.region {
            Some(ManagedResourceChecksumRegion::KastManagedFence) => {
                kast_managed_fence_sha256(path)?
            }
            None => sha256_file(path)?,
        };
        if actual != output.sha256 {
            issues.push(format!(
                "{} output checksum mismatch at {}",
                resource.kind,
                path.display()
            ));
        }
    }
    Ok(ManagedResourceVerification {
        ok: issues.is_empty(),
        issues,
    })
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

fn tool_name() -> String {
    TOOL_NAME.to_string()
}

fn default_profile() -> String {
    DEFAULT_PROFILE.to_string()
}

fn schema_version() -> u32 {
    SCHEMA_VERSION
}

pub fn home_dir() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
}

pub fn normalize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
    .components()
    .collect()
}

use crate::SCHEMA_VERSION;
use crate::bundle::{
    BundleVersion, HEADLESS_BACKEND_ARCHIVE_ROOT, HEADLESS_BACKEND_LAUNCHER,
    UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT, ubuntu_debian_headless_manifest,
};
use crate::cli::{PackageArgs, PackageCommand, UbuntuDebianBundlePackageArgs};
use crate::config;
use crate::error::{CliError, Result};
use flate2::Compression;
use flate2::write::GzEncoder;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command as ProcessCommand;

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum PackageResult {
    UbuntuDebianBundle(UbuntuDebianBundlePackageResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UbuntuDebianBundlePackageResult {
    pub output: String,
    pub sha256_sidecar: String,
    pub version: String,
    pub platform: String,
    pub manifest_schema_version: u32,
    pub cli_archive: String,
    pub backend_archive: String,
    pub plugin_archive: String,
    pub bundle_sha256: String,
    pub schema_version: u32,
}

pub fn run(args: PackageArgs) -> Result<PackageResult> {
    match args.command {
        PackageCommand::UbuntuDebianBundle(args) => {
            package_ubuntu_debian_bundle(args).map(PackageResult::UbuntuDebianBundle)
        }
    }
}

pub fn package_ubuntu_debian_bundle(
    args: UbuntuDebianBundlePackageArgs,
) -> Result<UbuntuDebianBundlePackageResult> {
    let cli_archive = config::normalize(args.cli_archive);
    let backend_archive = config::normalize(args.backend_archive);
    let plugin_archive = config::normalize(args.plugin_archive);
    require_file(&cli_archive, "CLI archive")?;
    require_file(&backend_archive, "backend archive")?;
    require_file(&plugin_archive, "IDEA plugin archive")?;
    let version = BundleVersion::parse(&args.version)
        .map_err(|message| CliError::new("CLI_USAGE", format!("Package version {message}.")))?;
    let platform = args.platform.trim();
    if platform.is_empty()
        || !platform
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        return Err(CliError::new("CLI_USAGE", "Package platform is invalid."));
    }
    let repo_root = args
        .repo_root
        .map(config::normalize)
        .unwrap_or_else(|| env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
    let bundle_name = format!("kast-{platform}-{}", version.as_str());
    let output = args
        .bundle_output
        .map(config::normalize)
        .unwrap_or_else(|| repo_root.join("dist").join(format!("{bundle_name}.tar.gz")));
    let mut sidecar = output.clone().into_os_string();
    sidecar.push(".sha256");
    let sidecar = PathBuf::from(sidecar);

    let scratch = ScratchDir::new("kast-package-ubuntu-debian")?;
    let cli_extract = scratch.path().join("cli");
    let backend_extract = scratch.path().join("backend");
    let staging_root = scratch.path().join(&bundle_name);
    fs::create_dir_all(&cli_extract)?;
    fs::create_dir_all(&backend_extract)?;
    fs::create_dir_all(staging_root.join("bin"))?;
    fs::create_dir_all(staging_root.join("lib/backends"))?;
    fs::create_dir_all(staging_root.join("plugins"))?;
    fs::create_dir_all(staging_root.join("skills"))?;
    fs::create_dir_all(staging_root.join("guidance"))?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }

    extract_zip_archive(&cli_archive, &cli_extract)?;
    extract_zip_archive(&backend_archive, &backend_extract)?;

    let cli_bin = cli_extract.join("kast");
    require_file(&cli_bin, "CLI archive root kast binary")?;
    let backend_root = backend_extract.join(HEADLESS_BACKEND_ARCHIVE_ROOT);
    validate_backend_archive_root(&backend_root)?;

    fs::copy(&cli_bin, staging_root.join("bin/kast"))?;
    make_executable(&staging_root.join("bin/kast"))?;
    let backend_install_name = format!("headless-{}", version.as_str());
    let backend_install_dir = staging_root
        .join("lib/backends")
        .join(&backend_install_name);
    copy_dir_recursive(&backend_root, &backend_install_dir)?;
    make_executable(&backend_install_dir.join(HEADLESS_BACKEND_LAUNCHER))?;

    fs::copy(&plugin_archive, staging_root.join("plugins/kast.zip"))?;
    copy_dir_recursive(
        &repo_root.join("cli-rs/resources/kast-skill"),
        &staging_root.join("skills/kast"),
    )?;
    copy_dir_recursive(
        &repo_root.join("cli-rs/resources/codex-plugin"),
        &staging_root.join("guidance/codex-plugin"),
    )?;

    let installer = repo_root.join(UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT);
    require_file(&installer, "setup bootstrap installer")?;
    fs::copy(
        &installer,
        staging_root.join(UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT),
    )?;
    make_executable(&staging_root.join(UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT))?;
    copy_license(&repo_root, &staging_root)?;

    let cli_sha = path_sha256(&staging_root.join("bin/kast"))?;
    let backend_sha = path_sha256(&backend_install_dir)?;
    let plugin_sha = path_sha256(&staging_root.join("plugins/kast.zip"))?;
    let skill_sha = path_sha256(&staging_root.join("skills/kast"))?;
    let guidance_sha = path_sha256(&staging_root.join("guidance"))?;
    let manifest = ubuntu_debian_headless_manifest(
        version.as_str(),
        platform,
        [cli_sha, backend_sha, plugin_sha, skill_sha, guidance_sha],
        build_commit(&repo_root),
    );
    fs::write(
        staging_root.join("manifest.json"),
        format!("{}\n", serde_json::to_string_pretty(&manifest)?),
    )?;

    remove_if_exists(&output)?;
    remove_if_exists(&sidecar)?;
    write_tar_gz(&staging_root, &bundle_name, &output)?;
    let bundle_sha = file_sha256(&output)?;
    fs::write(
        &sidecar,
        format!(
            "{}  {}\n",
            bundle_sha,
            output
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("bundle.tar.gz")
        ),
    )?;

    Ok(UbuntuDebianBundlePackageResult {
        output: output.display().to_string(),
        sha256_sidecar: sidecar.display().to_string(),
        version: version.into_string(),
        platform: platform.to_string(),
        manifest_schema_version: manifest.schema_version,
        cli_archive: cli_archive.display().to_string(),
        backend_archive: backend_archive.display().to_string(),
        plugin_archive: plugin_archive.display().to_string(),
        bundle_sha256: bundle_sha,
        schema_version: SCHEMA_VERSION,
    })
}

fn extract_zip_archive(archive_path: &Path, output_dir: &Path) -> Result<()> {
    let file = fs::File::open(archive_path)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "PACKAGE_ARCHIVE_INVALID",
            format!("Invalid zip archive {}: {error}", archive_path.display()),
        )
    })?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|error| {
            CliError::new(
                "PACKAGE_ARCHIVE_INVALID",
                format!("Invalid zip entry in {}: {error}", archive_path.display()),
            )
        })?;
        let Some(enclosed_name) = entry.enclosed_name() else {
            return Err(CliError::new(
                "PACKAGE_ARCHIVE_INVALID",
                format!(
                    "unsafe zip member in {}: {}",
                    archive_path.display(),
                    entry.name()
                ),
            ));
        };
        if zip_entry_is_symlink(entry.unix_mode()) {
            return Err(CliError::new(
                "PACKAGE_ARCHIVE_INVALID",
                format!(
                    "zip archive {} must not contain symlink member {}",
                    archive_path.display(),
                    entry.name()
                ),
            ));
        }
        let target = output_dir.join(enclosed_name);
        if entry.is_dir() {
            fs::create_dir_all(&target)?;
            continue;
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut output = fs::File::create(&target)?;
        std::io::copy(&mut entry, &mut output)?;
        if let Some(mode) = entry.unix_mode() {
            set_mode(&target, mode & 0o777)?;
        }
    }
    Ok(())
}

fn validate_backend_archive_root(backend_root: &Path) -> Result<()> {
    require_directory(backend_root, "backend archive root backend-headless")?;
    require_file(
        &backend_root.join("runtime-libs/classpath.txt"),
        "backend runtime-libs/classpath.txt",
    )?;
    require_file(
        &backend_root.join(HEADLESS_BACKEND_LAUNCHER),
        "headless backend launcher",
    )?;
    require_file(
        &backend_root.join("idea-home/lib/nio-fs.jar"),
        "headless IDEA nio-fs.jar",
    )?;
    require_file(
        &backend_root.join("idea-home/modules/module-descriptors.dat"),
        "headless IDEA module descriptors",
    )?;
    require_directory(
        &backend_root.join("idea-home/plugins/kast-headless"),
        "bundled kast-headless plugin",
    )?;
    Ok(())
}

fn copy_license(repo_root: &Path, staging_root: &Path) -> Result<()> {
    let license = repo_root.join("LICENSE");
    if license.is_file() {
        fs::copy(license, staging_root.join("LICENSE"))?;
    } else {
        fs::write(
            staging_root.join("LICENSE"),
            "Kast distribution notice\n\nSPDX-License-Identifier: Apache-2.0\nLicense text: https://www.apache.org/licenses/LICENSE-2.0\n",
        )?;
    }
    Ok(())
}

fn write_tar_gz(source_dir: &Path, archive_root_name: &str, output: &Path) -> Result<()> {
    let file = fs::File::create(output)?;
    let encoder = GzEncoder::new(file, Compression::default());
    let mut archive = tar::Builder::new(encoder);
    archive
        .append_dir_all(archive_root_name, source_dir)
        .map_err(|error| {
            CliError::new(
                "PACKAGE_ARCHIVE_FAILED",
                format!(
                    "Could not write bundle archive {}: {error}",
                    output.display()
                ),
            )
        })?;
    archive.finish().map_err(|error| {
        CliError::new(
            "PACKAGE_ARCHIVE_FAILED",
            format!(
                "Could not finish bundle archive {}: {error}",
                output.display()
            ),
        )
    })?;
    let encoder = archive.into_inner().map_err(|error| {
        CliError::new(
            "PACKAGE_ARCHIVE_FAILED",
            format!(
                "Could not finish bundle archive {}: {error}",
                output.display()
            ),
        )
    })?;
    encoder.finish().map_err(|error| {
        CliError::new(
            "PACKAGE_ARCHIVE_FAILED",
            format!(
                "Could not finish bundle archive {}: {error}",
                output.display()
            ),
        )
    })?;
    Ok(())
}

fn copy_dir_recursive(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "PACKAGE_ARCHIVE_INVALID",
            format!(
                "Archive content must not contain symlinks: {}",
                source.display()
            ),
        ));
    }
    if metadata.is_dir() {
        fs::create_dir_all(target)?;
        let mut entries = fs::read_dir(source)?.collect::<std::result::Result<Vec<_>, _>>()?;
        entries.sort_by_key(|entry| entry.path());
        for entry in entries {
            copy_dir_recursive(&entry.path(), &target.join(entry.file_name()))?;
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

fn file_sha256(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn path_sha256(path: &Path) -> Result<String> {
    if path.is_file() {
        return file_sha256(path);
    }
    require_directory(path, "artifact directory")?;
    let mut files = Vec::new();
    fn collect(root: &Path, directory: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            let metadata = fs::symlink_metadata(entry.path())?;
            if metadata.file_type().is_symlink() {
                return Err(CliError::new(
                    "PACKAGE_ARCHIVE_INVALID",
                    format!("Artifact contains a symlink: {}", entry.path().display()),
                ));
            }
            if metadata.is_dir() {
                collect(root, &entry.path(), files)?;
            } else if metadata.is_file() {
                files.push(
                    entry
                        .path()
                        .strip_prefix(root)
                        .expect("artifact child")
                        .to_path_buf(),
                );
            }
        }
        Ok(())
    }
    collect(path, path, &mut files)?;
    files.sort();
    let mut digest = Sha256::new();
    for relative in files {
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update(b"\n");
        digest.update(file_sha256(&path.join(&relative))?.as_bytes());
        digest.update(b"\n");
    }
    Ok(hex::encode(digest.finalize()))
}

fn build_commit(repo_root: &Path) -> String {
    ProcessCommand::new("git")
        .arg("-C")
        .arg(repo_root)
        .args(["rev-parse", "HEAD"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

fn require_file(path: &Path, label: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(CliError::new(
            "PACKAGE_INPUT_MISSING",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_directory(path: &Path, label: &str) -> Result<()> {
    if path.is_dir() {
        Ok(())
    } else {
        Err(CliError::new(
            "PACKAGE_INPUT_MISSING",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn remove_if_exists(path: &Path) -> Result<()> {
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

struct ScratchDir {
    path: PathBuf,
}

impl ScratchDir {
    fn new(label: &str) -> Result<Self> {
        let suffix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or_default();
        let path = env::temp_dir().join(format!("{label}-{}-{suffix}", std::process::id()));
        remove_if_exists(&path)?;
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

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    set_mode(path, 0o755)
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(mode);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<()> {
    Ok(())
}

fn zip_entry_is_symlink(mode: Option<u32>) -> bool {
    mode.is_some_and(|mode| mode & 0o170000 == 0o120000)
}

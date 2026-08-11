use crate::SCHEMA_VERSION;
use crate::bundle::{
    AGENT_CLI_BUNDLE_PATH, BundleVersion, CONTROL_CLI_BUNDLE_PATH, INDEXER_ARCHIVE_ROOT,
    INDEXER_LAUNCHER, SETUP_ENTRYPOINT, setup_bundle_manifest,
};
use crate::cli::{PackageArgs, PackageCommand, SetupBundlePackageArgs};
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
    SetupBundle(SetupBundlePackageResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupBundlePackageResult {
    pub output: String,
    pub sha256_sidecar: String,
    pub version: String,
    pub platform: String,
    pub manifest_schema_version: u32,
    pub cli_archive: String,
    pub indexer_archive: String,
    pub bundle_sha256: String,
    pub schema_version: u32,
}

pub fn run(args: PackageArgs) -> Result<PackageResult> {
    match args.command {
        PackageCommand::SetupBundle(args) => {
            package_setup_bundle(args).map(PackageResult::SetupBundle)
        }
    }
}

pub fn package_setup_bundle(args: SetupBundlePackageArgs) -> Result<SetupBundlePackageResult> {
    let cli_archive = config::normalize(args.cli_archive);
    let indexer_archive = config::normalize(args.indexer_archive);
    require_file(&cli_archive, "CLI archive")?;
    require_file(&indexer_archive, "indexer archive")?;
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

    let scratch = ScratchDir::new("kast-package-setup")?;
    let cli_extract = scratch.path().join("cli");
    let indexer_extract = scratch.path().join("indexer");
    let staging_root = scratch.path().join(&bundle_name);
    fs::create_dir_all(&cli_extract)?;
    fs::create_dir_all(&indexer_extract)?;
    fs::create_dir_all(staging_root.join("bin"))?;
    fs::create_dir_all(staging_root.join("libexec"))?;
    fs::create_dir_all(staging_root.join("lib/backends"))?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }

    extract_zip_archive(&cli_archive, &cli_extract)?;
    extract_zip_archive(&indexer_archive, &indexer_extract)?;

    let cli_bin = cli_extract.join("kastctl");
    let agent_cli_bin = cli_extract.join("kast");
    require_file(&cli_bin, "CLI archive root kastctl binary")?;
    require_file(&agent_cli_bin, "CLI archive root kast binary")?;
    if file_sha256(&cli_bin)? != file_sha256(&agent_cli_bin)? {
        return Err(CliError::new(
            "CLI_ARCHIVE_INVALID",
            "CLI archive root kastctl and kast binaries must be byte-identical.",
        ));
    }
    let indexer_root = indexer_extract.join(INDEXER_ARCHIVE_ROOT);
    validate_indexer_archive_root(&indexer_root)?;

    fs::copy(&cli_bin, staging_root.join(CONTROL_CLI_BUNDLE_PATH))?;
    make_executable(&staging_root.join(CONTROL_CLI_BUNDLE_PATH))?;
    fs::copy(&agent_cli_bin, staging_root.join(AGENT_CLI_BUNDLE_PATH))?;
    make_executable(&staging_root.join(AGENT_CLI_BUNDLE_PATH))?;
    let indexer_install_name = format!("indexer-{}", version.as_str());
    let indexer_install_dir = staging_root
        .join("lib/backends")
        .join(&indexer_install_name);
    stage_indexer_for_platform(&indexer_root, &indexer_install_dir, platform)?;
    make_executable(&indexer_install_dir.join(INDEXER_LAUNCHER))?;

    let installer = repo_root.join(SETUP_ENTRYPOINT);
    require_file(&installer, "setup bootstrap installer")?;
    fs::copy(&installer, staging_root.join(SETUP_ENTRYPOINT))?;
    make_executable(&staging_root.join(SETUP_ENTRYPOINT))?;
    copy_license(&repo_root, &staging_root)?;

    let cli_sha = path_sha256(&staging_root.join(CONTROL_CLI_BUNDLE_PATH))?;
    let agent_cli_sha = path_sha256(&staging_root.join(AGENT_CLI_BUNDLE_PATH))?;
    let indexer_sha = path_sha256(&indexer_install_dir)?;
    let manifest = setup_bundle_manifest(
        version.as_str(),
        platform,
        [cli_sha, agent_cli_sha, indexer_sha],
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

    Ok(SetupBundlePackageResult {
        output: output.display().to_string(),
        sha256_sidecar: sidecar.display().to_string(),
        version: version.into_string(),
        platform: platform.to_string(),
        manifest_schema_version: manifest.schema_version,
        cli_archive: cli_archive.display().to_string(),
        indexer_archive: indexer_archive.display().to_string(),
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

fn validate_indexer_archive_root(indexer_root: &Path) -> Result<()> {
    require_directory(indexer_root, "indexer archive root")?;
    require_file(
        &indexer_root.join("runtime-libs/classpath.txt"),
        "indexer runtime-libs/classpath.txt",
    )?;
    require_file(&indexer_root.join(INDEXER_LAUNCHER), "indexer launcher")?;
    require_file(
        &indexer_root.join("idea-home/lib/nio-fs.jar"),
        "indexer host nio-fs.jar",
    )?;
    require_file(
        &indexer_root.join("idea-home/modules/module-descriptors.dat"),
        "indexer host module descriptors",
    )?;
    require_directory(
        &indexer_root.join("idea-home/plugins/kast-indexer"),
        "bundled Kast indexer runtime payload",
    )?;
    Ok(())
}

fn stage_indexer_for_platform(source: &Path, target: &Path, platform: &str) -> Result<()> {
    if !platform.starts_with("macos-") {
        return copy_dir_recursive(source, target);
    }
    fs::create_dir_all(target.join("idea-home/plugins"))?;
    fs::copy(source.join(INDEXER_LAUNCHER), target.join(INDEXER_LAUNCHER))?;
    copy_dir_recursive(
        &source.join("idea-home/plugins/kast-indexer"),
        &target.join("idea-home/plugins/kast-indexer"),
    )
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
    let encoder = GzEncoder::new(file, Compression::fast());
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

include!("parts/package/filesystem.rs");

#[cfg(test)]
mod sidecar_tests {
    use super::*;

    const KOTLIN_JPS_SIDECAR_PATH: &str =
        "idea-home/plugins/kast-indexer/lib/kotlin-jps-plugin.jar";

    #[test]
    fn macos_bundle_stages_only_the_indexer_payload() {
        let temp = tempfile::tempdir().expect("tempdir");
        let source = temp.path().join("source");
        let target = temp.path().join("target");
        fs::create_dir_all(source.join("runtime-libs")).expect("runtime libs");
        fs::create_dir_all(source.join("idea-home/lib")).expect("IDEA libs");
        fs::create_dir_all(source.join("idea-home/plugins/kast-indexer/lib"))
            .expect("sidecar plugin");
        fs::write(source.join(INDEXER_LAUNCHER), "launcher").expect("launcher");
        fs::write(source.join("runtime-libs/classpath.txt"), "runtime").expect("classpath");
        fs::write(source.join("idea-home/lib/nio-fs.jar"), "platform").expect("platform");
        fs::write(
            source.join("idea-home/plugins/kast-indexer/lib/kast-indexer.jar"),
            "payload",
        )
        .expect("payload");
        fs::write(source.join(KOTLIN_JPS_SIDECAR_PATH), "Kotlin JPS").expect("Kotlin JPS payload");

        stage_indexer_for_platform(&source, &target, "macos-arm64").expect("stage sidecar");

        assert!(target.join(INDEXER_LAUNCHER).is_file());
        assert!(
            target
                .join("idea-home/plugins/kast-indexer/lib/kast-indexer.jar")
                .is_file()
        );
        assert_eq!(
            fs::read_to_string(
                target.join("idea-home/plugins/kast-indexer/lib/kotlin-jps-plugin.jar"),
            )
            .expect("bundled Kotlin JPS payload"),
            "Kotlin JPS",
        );
        assert!(!target.join("runtime-libs").exists());
        assert!(!target.join("idea-home/lib").exists());

        let manifest = setup_bundle_manifest(
            "1.2.3",
            "macos-arm64",
            ["0".repeat(64), "1".repeat(64), "2".repeat(64)],
            "commit".to_string(),
        );
        assert_eq!(manifest.profile, "indexer");
        assert!(
            manifest
                .java_requirement
                .contains("supported installed IntelliJ")
        );
    }
}

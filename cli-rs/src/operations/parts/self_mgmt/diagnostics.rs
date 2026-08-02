
fn configuration_diagnostic(
    config_path: &Path,
    error: Option<String>,
) -> DoctorConfigurationDiagnostic {
    DoctorConfigurationDiagnostic {
        config_home: config::kast_config_home().display().to_string(),
        config_path: config_path.display().to_string(),
        exists: config_path.is_file(),
        valid: error.is_none(),
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn canonical_directory_diagnostic(
    paths: &config::PathsConfig,
) -> DoctorCanonicalDirectoryDiagnostic {
    DoctorCanonicalDirectoryDiagnostic {
        root: paths.install_root.display().to_string(),
        bin_dir: paths.bin_dir.display().to_string(),
        lib_dir: paths.lib_dir.display().to_string(),
        cache_dir: paths.cache_dir.display().to_string(),
        logs_dir: paths.logs_dir.display().to_string(),
        runtime_dir: paths.runtime_dir.display().to_string(),
        descriptor_dir: paths.descriptor_dir.display().to_string(),
        socket_dir: paths.socket_dir.display().to_string(),
        schema_version: SCHEMA_VERSION,
    }
}

fn binary_diagnostic(
    cli: &config::CliConfig,
    install: Option<&InstallState>,
) -> DoctorBinaryDiagnostic {
    let running_binary = env::current_exe().unwrap_or_else(|_| cli.binary_path.clone());
    let configured_binary = cli.binary_path.clone();
    let configured_exists = configured_binary.is_file();
    let configured_matches_running = configured_exists
        && configured_binary_matches_running(
            &configured_binary,
            &running_binary,
            install.map(|install| Path::new(&install.entrypoints.active_binary)),
        );
    DoctorBinaryDiagnostic {
        running_binary: running_binary.display().to_string(),
        configured_binary: configured_binary.display().to_string(),
        configured_exists,
        configured_matches_running,
        schema_version: SCHEMA_VERSION,
    }
}

fn configured_binary_matches_running(
    configured_binary: &Path,
    running_binary: &Path,
    active_binary: Option<&Path>,
) -> bool {
    cli_binary_matches_running(configured_binary, running_binary)
        || active_binary
            .is_some_and(|active_binary| cli_binary_matches_running(active_binary, running_binary))
}

fn cli_binary_matches_running(authority_binary: &Path, running_binary: &Path) -> bool {
    same_binary_path(authority_binary, running_binary)
        || private_control_public_entrypoint(authority_binary)
            .is_some_and(|public| same_binary_path(&public, running_binary))
}

fn private_control_public_entrypoint(authority_binary: &Path) -> Option<PathBuf> {
    if authority_binary.file_name()?.to_str()? != "kastctl"
        || authority_binary.parent()?.file_name()?.to_str()? != "libexec"
    {
        return None;
    }
    Some(authority_binary.parent()?.parent()?.join("bin/kast"))
}

fn same_binary_path(left: &Path, right: &Path) -> bool {
    if config::normalize(left.to_path_buf()) == config::normalize(right.to_path_buf()) {
        return true;
    }
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

pub fn read_global_install_state() -> Result<Option<InstallState>> {
    manifest::read_install_manifest()
}

fn managed_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn minimum_backend_version() -> &'static str {
    option_env!("KAST_MIN_DAEMON_VERSION").unwrap_or("0.7.11")
}

fn version_meets_minimum(version: &str, minimum: &str) -> Option<bool> {
    Some(parse_version_triplet(version)? >= parse_version_triplet(minimum)?)
}

fn parse_version_triplet(value: &str) -> Option<(u64, u64, u64)> {
    let normalized = value.trim().trim_start_matches('v');
    let mut parts = normalized.split(['.', '-', '+']);
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next()?.parse().ok()?;
    Some((major, minor, patch))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn configured_binary_match_accepts_manifest_active_binary() {
        let configured_binary = Path::new("/example/bin/kast");
        let running_binary = Path::new("/example/versions/0.1.0/bin/kast");

        assert!(configured_binary_matches_running(
            configured_binary,
            running_binary,
            Some(running_binary)
        ));
        assert!(!configured_binary_matches_running(
            configured_binary,
            Path::new("/other/bin/kast"),
            Some(running_binary)
        ));
    }
}

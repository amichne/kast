fn validate_bundle(root: &Path) -> Result<ValidatedBundle> {
    let manifest = read_bundle_manifest(root)?;
    let version = validate_bundle_manifest_header(&manifest)?;
    validate_bundle_artifacts(root, &manifest)?;

    let cli_relative = bundle_manifest_path(&manifest.activation.cli.path, "activation.cli.path")?;
    let entrypoint_relative = bundle_manifest_path(&manifest.entrypoint, "entrypoint")?;
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
    require_executable(&root.join(entrypoint_relative), "bundle setup entrypoint")?;
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

    let release_digest = directory_sha256(root)?;
    let manifest_digest = manifest::sha256_file(&root.join(BUNDLE_MANIFEST_FILE))?;

    Ok(ValidatedBundle {
        root: root.to_path_buf(),
        manifest,
        version,
        cli_relative,
        backend_install_relative,
        release_digest,
        manifest_digest,
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
    if !matches!(
        manifest.platform.as_str(),
        UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID
            | "linux-x64"
            | "linux-arm64"
            | "macos-x64"
            | "macos-arm64"
    ) {
        return Err(CliError::new(
            "BUNDLE_PLATFORM_UNSUPPORTED",
            format!("Unsupported bundle platform `{}`.", manifest.platform),
        ));
    }
    let _entrypoint = bundle_manifest_path(&manifest.entrypoint, "entrypoint")?;
    Ok(version)
}

fn validate_bundle_artifacts(root: &Path, manifest: &BundleManifest) -> Result<()> {
    let mut roles = BTreeSet::new();
    for artifact in &manifest.artifacts {
        if artifact.role.trim().is_empty() {
            return Err(CliError::new(
                "BUNDLE_MANIFEST_INVALID",
                "Bundle artifact role must not be empty.",
            ));
        }
        let relative = bundle_manifest_path(&artifact.path, "artifacts[].path")?;
        if artifact.sha256.len() != 64
            || !artifact
                .sha256
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(CliError::new(
                "BUNDLE_MANIFEST_INVALID",
                format!(
                    "Bundle artifact `{}` must record a lowercase SHA-256 digest.",
                    artifact.role
                ),
            ));
        }
        let path = root.join(relative);
        let actual = if path.is_file() {
            manifest::sha256_file(&path)?
        } else if path.is_dir() {
            directory_sha256(&path)?
        } else {
            return Err(CliError::new(
                "BUNDLE_SHAPE_INVALID",
                format!("Missing bundle artifact `{}` at {}.", artifact.role, path.display()),
            ));
        };
        if actual != artifact.sha256 {
            return Err(CliError::new(
                "BUNDLE_ARTIFACT_MISMATCH",
                format!("Bundle artifact `{}` does not match its manifest digest.", artifact.role),
            ));
        }
        roles.insert(artifact.role.as_str());
    }
    for role in ["cli", "headless-backend", "plugin"] {
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
    install_root: PathBuf,
    bundle: &ValidatedBundle,
) -> Result<ActivationTargetPaths> {
    let install_root = config::normalize(install_root);
    let state_dir = install_root.join("state");
    let cache_dir = state_dir.join("cache");
    let runtime_dir = state_dir.join("runtime");
    let logs_dir = state_dir.join("logs");
    let locks_dir = install_root.clone();
    let version_dir = install_root.join("releases").join(&bundle.release_digest);
    let current_link = install_root.join("current");
    let config_root = current_link.join("config");
    let bin_dir = current_link.join("bin");
    let previous_link = install_root.join("previous");
    let headless_current_dir = version_dir.join("lib/backends/headless/current");
    let lib_dir = current_link.join("lib");
    let resolved = manifest::ResolvedKastPaths {
        install_root: install_root.clone(),
        bin_dir: bin_dir.clone(),
        lib_dir,
        data_dir: state_dir.join("data"),
        cache_dir,
        logs_dir,
        runtime_dir: runtime_dir.clone(),
        locks_dir,
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: current_link.join(&bundle.cli_relative),
        active_binary: current_link.join(&bundle.cli_relative),
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

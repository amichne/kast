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

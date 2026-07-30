#[derive(Debug)]
struct ForceResetPlan {
    targets: BTreeSet<PathBuf>,
    requires_closed_ide: bool,
}

impl ForceResetPlan {
    fn build(
        targets: &ActivationTargetPaths,
        selected_idea_plugins_dir: Option<&Path>,
    ) -> Result<Self> {
        let install_root = config::normalize(targets.resolved.install_root.clone());
        let home = normalized_existing_path(manifest::home_dir());
        let mut cleanup = BTreeSet::new();

        for name in ["backups", "current", "previous", "releases", "staging", "state"] {
            cleanup.insert(validated_child(&install_root, name, "Kast install state")?);
        }

        let receipt_owned = manifest_from_file(
            &targets.current_link.join(manifest::INSTALL_MANIFEST_FILE),
        )
        .map(|receipt| receipt.owned_paths)
        .unwrap_or_default();
        let user_bin = home.join(".local/bin");
        for name in ["kast", "_kastctl"] {
            let command = validated_child(&user_bin, name, "Kast user command")?;
            if managed_user_command(&command, &install_root, &receipt_owned) {
                cleanup.insert(command);
            }
        }

        let mut workspace_roots = registered_workspace_roots(&install_root)?;
        let current = normalized_existing_path(env::current_dir()?);
        workspace_roots.insert(home.clone());
        workspace_roots.insert(current.clone());
        if current.starts_with(&home) {
            for ancestor in current.ancestors() {
                workspace_roots.insert(ancestor.to_path_buf());
                if ancestor == home {
                    break;
                }
            }
        }
        for root in workspace_roots {
            if let Some(metadata) = workspace_metadata_target(&root) {
                cleanup.insert(metadata);
            }
        }

        let mut plugin_directories = supported_idea_plugins_dirs()?;
        if let Some(selected) = selected_idea_plugins_dir {
            plugin_directories.push(config::normalize(selected.to_path_buf()));
        }
        plugin_directories.sort();
        plugin_directories.dedup();
        let mut requires_closed_ide = false;
        for plugins in plugin_directories {
            let plugin = validated_child(&plugins, "kast", "Kast IDEA plugin")?;
            let profile = plugins.parent().ok_or_else(|| {
                CliError::new(
                    "FORCE_RESET_TARGET_INVALID",
                    format!("IDEA plugins directory has no profile parent: {}", plugins.display()),
                )
            })?;
            let backup = validated_child(profile, ".kast-plugin-backup", "Kast IDEA plugin backup")?;
            requires_closed_ide |= fs::symlink_metadata(&plugin).is_ok()
                || fs::symlink_metadata(&backup).is_ok();
            cleanup.insert(plugin);
            cleanup.insert(backup);
        }

        Ok(Self {
            targets: cleanup,
            requires_closed_ide,
        })
    }

    fn execute(self) -> Result<()> {
        if self.requires_closed_ide {
            require_jetbrains_ides_closed()?;
        }
        for target in self.targets {
            manifest::remove_path(&target)?;
        }
        Ok(())
    }
}

fn require_force_source_outside_install_root(
    mode: SetupMode,
    source: &Path,
    install_root: &Path,
) -> Result<()> {
    if mode.is_force() && config::normalize(source.to_path_buf()).starts_with(install_root) {
        return Err(CliError::new(
            "FORCE_SETUP_SOURCE_UNSAFE",
            format!(
                "Forced setup source {} is inside the Kast install root {}; rerun install.sh --force with an external release source.",
                source.display(),
                install_root.display(),
            ),
        ));
    }
    Ok(())
}

fn validated_child(parent: &Path, name: &str, label: &str) -> Result<PathBuf> {
    let parent = config::normalize(parent.to_path_buf());
    let target = config::normalize(parent.join(name));
    if !parent.is_absolute()
        || name.contains('/')
        || name.contains('\\')
        || target.parent() != Some(parent.as_path())
        || target.file_name().and_then(|value| value.to_str()) != Some(name)
    {
        return Err(CliError::new(
            "FORCE_RESET_TARGET_INVALID",
            format!("{label} is not an exact child of {}.", parent.display()),
        ));
    }
    Ok(target)
}

fn managed_user_command(command: &Path, install_root: &Path, owned: &[String]) -> bool {
    if owned.iter().any(|path| Path::new(path) == command) {
        return true;
    }
    fs::read_link(command).is_ok_and(|target| {
        let target = if target.is_absolute() {
            target
        } else {
            command
                .parent()
                .unwrap_or_else(|| Path::new("."))
                .join(target)
        };
        config::normalize(target).starts_with(install_root)
    })
}

fn workspace_metadata_target(root: &Path) -> Option<PathBuf> {
    if !root.is_absolute() {
        return None;
    }
    let root = normalized_existing_path(root.to_path_buf());
    root.parent()?;
    validated_child(&root, ".kast", "Kast workspace metadata").ok()
}

fn normalized_existing_path(path: PathBuf) -> PathBuf {
    fs::canonicalize(&path).unwrap_or_else(|_| config::normalize(path))
}

fn registered_workspace_roots(install_root: &Path) -> Result<BTreeSet<PathBuf>> {
    let state = install_root.join("state");
    let mut roots = BTreeSet::new();
    let local_registry = state.join("data/workspaces/local-workspaces.json");
    if let Some(document) = read_json_if_present(&local_registry)?
        && let Some(registry) = document.as_object()
    {
        roots.extend(registry.keys().map(PathBuf::from));
    }
    let daemons = state.join("runtime/daemons/daemons.json");
    if let Some(document) = read_json_if_present(&daemons)? {
        collect_workspace_roots_from_json(&document, &mut roots);
    }
    collect_workspace_metadata_roots(
        &state.join("data/workspaces"),
        &mut roots,
    )?;
    Ok(roots)
}

fn read_json_if_present(path: &Path) -> Result<Option<serde_json::Value>> {
    match fs::read_to_string(path) {
        Ok(raw) => Ok(serde_json::from_str(&raw).ok()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn collect_workspace_metadata_roots(
    directory: &Path,
    roots: &mut BTreeSet<PathBuf>,
) -> Result<()> {
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    for entry in entries {
        let entry = entry?;
        let file_type = entry.file_type()?;
        if file_type.is_dir() && !file_type.is_symlink() {
            collect_workspace_metadata_roots(&entry.path(), roots)?;
        } else if file_type.is_file()
            && entry.file_name().to_str() == Some("workspace.json")
            && let Some(document) = read_json_if_present(&entry.path())?
        {
            collect_workspace_roots_from_json(&document, roots);
        }
    }
    Ok(())
}

fn collect_workspace_roots_from_json(
    value: &serde_json::Value,
    roots: &mut BTreeSet<PathBuf>,
) {
    match value {
        serde_json::Value::Object(object) => {
            if let Some(root) = object.get("workspaceRoot").and_then(serde_json::Value::as_str) {
                roots.insert(PathBuf::from(root));
            }
            for nested in object.values() {
                collect_workspace_roots_from_json(nested, roots);
            }
        }
        serde_json::Value::Array(values) => {
            for nested in values {
                collect_workspace_roots_from_json(nested, roots);
            }
        }
        _ => {}
    }
}

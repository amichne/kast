#[derive(Debug)]
struct ForceResetPlan {
    targets: BTreeSet<PathBuf>,
}

impl ForceResetPlan {
    fn build(
        targets: &ActivationTargetPaths,
        runtime_setup_authorization: &crate::runtime::RuntimeSetupAuthorization,
    ) -> Result<Self> {
        if !runtime_setup_authorization
            .pinned_release_roots()
            .is_empty()
        {
            return Err(CliError::new(
                "SETUP_RUNTIME_NOT_QUIESCENT",
                "Forced setup cannot delete releases pinned by registered runtimes.",
            ));
        }
        let install_root = config::normalize(targets.resolved.install_root.clone());
        let mut cleanup = BTreeSet::new();

        for name in ["current", "previous", "releases", "staging", "state"] {
            cleanup.insert(validated_child(&install_root, name, "Kast install state")?);
        }

        Ok(Self { targets: cleanup })
    }

    fn execute(self) -> Result<()> {
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

use super::*;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(in super::super) enum WorkspaceRootCandidate {
    ExistingCanonical(PathBuf),
    MissingNormalized(PathBuf),
}

impl WorkspaceRootCandidate {
    pub(in super::super) fn resolve(workspace_root: &Path) -> Result<Self> {
        match fs::canonicalize(workspace_root) {
            Ok(root) => Ok(Self::ExistingCanonical(root)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let root = config::normalize(workspace_root.to_path_buf());
                if root.is_absolute() {
                    Ok(Self::MissingNormalized(root))
                } else {
                    Err(workspace_root_invalid(
                        workspace_root,
                        "could not be normalized to an absolute path",
                    ))
                }
            }
            Err(error) => Err(workspace_root_invalid(workspace_root, &error.to_string())),
        }
    }

    #[allow(dead_code)]
    pub(in super::super) fn path(&self) -> &Path {
        match self {
            Self::ExistingCanonical(root) | Self::MissingNormalized(root) => root,
        }
    }
}

#[derive(Debug, Clone)]
pub(in super::super) enum RegisteredWorkspaceRoot {
    ExistingCanonical(PathBuf),
    MissingRegistered {
        root: PathBuf,
        registrations: Vec<ValidatedServiceRegistration>,
    },
}

impl RegisteredWorkspaceRoot {
    pub(in super::super) fn admit(
        config: &KastConfig,
        candidate: WorkspaceRootCandidate,
    ) -> Result<Self> {
        match candidate {
            WorkspaceRootCandidate::ExistingCanonical(root) => Ok(Self::ExistingCanonical(root)),
            WorkspaceRootCandidate::MissingNormalized(root) => {
                let registrations = super::read_workspace_registrations(config, &root)?;
                if registrations.is_empty() {
                    return Err(workspace_root_invalid(
                        &root,
                        "is missing and has no validated Kast service registration",
                    ));
                }
                Ok(Self::MissingRegistered {
                    root,
                    registrations,
                })
            }
        }
    }

    pub(in super::super) fn revalidate(
        &self,
        config: &KastConfig,
    ) -> Result<(PathBuf, Vec<ValidatedServiceRegistration>)> {
        match self {
            Self::ExistingCanonical(expected) => {
                let current = require_existing_workspace_root(expected)?;
                if &current != expected {
                    return Err(ownership_changed(
                        "Workspace canonical identity changed before reconciliation.",
                    ));
                }
                let registrations = super::read_workspace_registrations(config, &current)?;
                Ok((current, registrations))
            }
            Self::MissingRegistered {
                root,
                registrations,
            } => {
                match fs::canonicalize(root) {
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                    Ok(_) => {
                        return Err(ownership_changed(
                            "A missing registered workspace reappeared before reconciliation.",
                        ));
                    }
                    Err(error) => return Err(workspace_root_invalid(root, &error.to_string())),
                }
                let current = super::read_workspace_registrations(config, root)?;
                if !same_registrations(registrations, &current) {
                    return Err(ownership_changed(
                        "Missing-workspace service registration changed before reconciliation.",
                    ));
                }
                Ok((root.clone(), current))
            }
        }
    }
}

pub(in super::super) fn require_existing_workspace_root(workspace_root: &Path) -> Result<PathBuf> {
    match WorkspaceRootCandidate::resolve(workspace_root)? {
        WorkspaceRootCandidate::ExistingCanonical(root) => Ok(root),
        WorkspaceRootCandidate::MissingNormalized(root) => Err(workspace_root_invalid(
            &root,
            "does not exist and has no admitted registration evidence",
        )),
    }
}

fn same_registrations(
    expected: &[ValidatedServiceRegistration],
    current: &[ValidatedServiceRegistration],
) -> bool {
    expected.len() == current.len()
        && expected.iter().all(|expected| {
            current.iter().any(|current| {
                current.directory == expected.directory
                    && current.receipt_sha256 == expected.receipt_sha256
                    && current.receipt == expected.receipt
                    && current.launch == expected.launch
            })
        })
}

fn workspace_root_invalid(workspace_root: &Path, reason: &str) -> CliError {
    CliError::new(
        "WORKSPACE_ROOT_INVALID",
        format!(
            "Workspace root {} could not be reconciled: {reason}",
            workspace_root.display()
        ),
    )
}

fn ownership_changed(message: &str) -> CliError {
    CliError::new("RUNTIME_OWNERSHIP_CHANGED", message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn admitted_missing_root_reappearance_fails_deleted_workspace_registration_review_regression() {
        let parent = tempfile::tempdir().expect("workspace parent");
        let root = parent.path().join("missing");
        let admitted = RegisteredWorkspaceRoot::MissingRegistered {
            root: root.clone(),
            registrations: vec![],
        };
        fs::create_dir(&root).expect("reappeared workspace");
        let config = KastConfig::load(parent.path()).expect("test config");

        let error = admitted
            .revalidate(&config)
            .expect_err("reappeared workspace must invalidate ownership");

        assert_eq!(error.code, "RUNTIME_OWNERSHIP_CHANGED");
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ControlReplacementState {
    PriorPublished,
    DesiredPublished,
}

impl ControlReplacementState {
    fn reversed(self) -> Self {
        match self {
            Self::PriorPublished => Self::DesiredPublished,
            Self::DesiredPublished => Self::PriorPublished,
        }
    }
}

fn control_replacement_state(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
) -> Result<ControlReplacementState> {
    let prior_is_published = projection_file_identity(control_path).ok() == Some(prior_identity)
        && exact_projection_matches(control_path, prior_target)
        && projection_file_identity(temporary_path).ok() == Some(projected_identity)
        && exact_projection_matches(temporary_path, desired_target);
    if prior_is_published {
        return Ok(ControlReplacementState::PriorPublished);
    }
    let desired_is_published = projection_file_identity(control_path).ok()
        == Some(projected_identity)
        && exact_projection_matches(control_path, desired_target)
        && projection_file_identity(temporary_path).ok() == Some(prior_identity)
        && exact_projection_matches(temporary_path, prior_target);
    if desired_is_published {
        return Ok(ControlReplacementState::DesiredPublished);
    }
    Err(projection_recovery_conflict(control_path, temporary_path))
}

fn require_control_replacement_state(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
    expected: ControlReplacementState,
) -> Result<()> {
    if control_replacement_state(
        control_path,
        temporary_path,
        prior_target,
        prior_identity,
        desired_target,
        projected_identity,
    )? == expected
    {
        Ok(())
    } else {
        Err(projection_recovery_conflict(control_path, temporary_path))
    }
}

#[derive(Debug, Clone, Copy)]
struct ProjectionExchangeSnapshot {
    control_identity: ProjectionFileIdentity,
    temporary_identity: ProjectionFileIdentity,
}

impl ProjectionExchangeSnapshot {
    fn for_state(
        state: ControlReplacementState,
        prior_identity: ProjectionFileIdentity,
        projected_identity: ProjectionFileIdentity,
    ) -> Self {
        match state {
            ControlReplacementState::PriorPublished => Self {
                control_identity: prior_identity,
                temporary_identity: projected_identity,
            },
            ControlReplacementState::DesiredPublished => Self {
                control_identity: projected_identity,
                temporary_identity: prior_identity,
            },
        }
    }

    fn require_at(self, control_path: &Path, temporary_path: &Path) -> Result<()> {
        require_identity(
            control_path,
            self.control_identity,
            "public projection selected for exchange restoration",
        )?;
        require_identity(
            temporary_path,
            self.temporary_identity,
            "temporary projection selected for exchange restoration",
        )
    }

    fn require_reversed(self, control_path: &Path, temporary_path: &Path) -> Result<()> {
        require_identity(
            control_path,
            self.temporary_identity,
            "restored public control projection",
        )?;
        require_identity(
            temporary_path,
            self.control_identity,
            "restored temporary control projection",
        )
    }
}

#[allow(clippy::too_many_arguments)]
fn exchange_control_projection(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
    expected_state: ControlReplacementState,
    durability_failure_point: &str,
) -> Result<()> {
    require_control_replacement_state(
        control_path,
        temporary_path,
        prior_target,
        prior_identity,
        desired_target,
        projected_identity,
        expected_state.reversed(),
    )?;
    rename_exchange(control_path, temporary_path)?;
    let validation = test_path_projection_barrier_at(
        "after-projection-exchange-before-validation",
        control_path,
    )
    .and_then(|()| {
        require_control_replacement_state(
            control_path,
            temporary_path,
            prior_target,
            prior_identity,
            desired_target,
            projected_identity,
            expected_state,
        )
    });
    match validation {
        Ok(()) => sync_projection_parent_after(control_path, durability_failure_point),
        Err(mut error) => {
            let expected_exchange = ProjectionExchangeSnapshot::for_state(
                expected_state,
                prior_identity,
                projected_identity,
            );
            let exchange_restored = match restore_projection_exchange(
                control_path,
                temporary_path,
                expected_exchange,
            ) {
                Ok(()) => true,
                Err(restoration_error) => {
                    error.message = format!(
                        "{} The exchanged paths could not be restored: {restoration_error}",
                        error.message,
                    );
                    error.details.insert(
                        "exchangeRestorationError".to_string(),
                        restoration_error.to_string(),
                    );
                    false
                }
            };
            error.details.insert(
                "exchangeRestored".to_string(),
                exchange_restored.to_string(),
            );
            Err(error)
        }
    }
}

fn restore_projection_exchange(
    control_path: &Path,
    temporary_path: &Path,
    expected_exchange: ProjectionExchangeSnapshot,
) -> Result<()> {
    expected_exchange.require_at(control_path, temporary_path)?;
    rename_exchange(control_path, temporary_path)?;
    let verification = expected_exchange.require_reversed(control_path, temporary_path);
    let durability = sync_projection_move_parents(control_path, temporary_path);
    if let Err(mut error) = verification {
        if let Err(durability_error) = durability {
            error.details.insert(
                "restorationDurabilityError".to_string(),
                durability_error.to_string(),
            );
        }
        return Err(error);
    }
    durability
}

fn remove_prepared_control_projection(path: &Path, expected_target: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => sync_projection_parent(path),
        Err(error) => Err(error.into()),
        Ok(_) => Err(prepared_projection_recovery_conflict(path, expected_target)),
    }
}

fn prepared_projection_recovery_conflict(path: &Path, expected_target: &Path) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Cannot remove unproven PATH projection transaction artifact {}; preserved the changed path.",
            path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error.details.insert(
        "expectedTarget".to_string(),
        expected_target.display().to_string(),
    );
    error
}

fn require_path_absent(path: &Path, label: &str) -> Result<()> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
        Ok(_) => Err(CliError::new(
            "PATH_PROJECTION_INTERNAL_PATH_OCCUPIED",
            format!("{label} is already occupied: {}.", path.display()),
        )),
    }
}

fn require_identity(path: &Path, expected: ProjectionFileIdentity, label: &str) -> Result<()> {
    if projection_file_identity(path).ok() == Some(expected) {
        Ok(())
    } else {
        Err(CliError::new(
            "PATH_PROJECTION_IDENTITY_CHANGED",
            format!("{label} identity changed at {}.", path.display()),
        ))
    }
}

fn sync_projection_move_parents(first: &Path, second: &Path) -> Result<()> {
    let first_result = manifest::sync_parent_directory(first);
    let second_result = manifest::sync_parent_directory(second);
    match (first_result, second_result) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) | (Ok(()), Err(error)) => Err(error),
        (Err(mut first_error), Err(second_error)) => {
            first_error.details.insert(
                "secondParentSyncError".to_string(),
                second_error.to_string(),
            );
            Err(first_error)
        }
    }
}

#[cfg(unix)]
fn projection_file_identity(path: &Path) -> Result<ProjectionFileIdentity> {
    use std::os::unix::fs::MetadataExt;
    let metadata = fs::symlink_metadata(path)?;
    let file_type = metadata.file_type();
    let kind = if file_type.is_symlink() {
        ProjectionFileKind::Symlink
    } else if file_type.is_file() {
        ProjectionFileKind::File
    } else if file_type.is_dir() {
        ProjectionFileKind::Directory
    } else {
        ProjectionFileKind::Other
    };
    Ok(ProjectionFileIdentity {
        device: metadata.dev(),
        inode: metadata.ino(),
        kind,
    })
}

#[cfg(not(unix))]
fn projection_file_identity(_path: &Path) -> Result<ProjectionFileIdentity> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Receipt-owned PATH projections require Unix filesystem identity.",
    ))
}

#[cfg(target_os = "macos")]
fn rename_no_replace(source: &Path, target: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source path contains NUL"))?;
    let target = CString::new(target.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "target path contains NUL"))?;
    let result = unsafe { libc::renamex_np(source.as_ptr(), target.as_ptr(), libc::RENAME_EXCL) };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "macos")]
fn rename_exchange(first: &Path, second: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let first = CString::new(first.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "first path contains NUL"))?;
    let second = CString::new(second.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "second path contains NUL"))?;
    let result = unsafe { libc::renamex_np(first.as_ptr(), second.as_ptr(), libc::RENAME_SWAP) };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "linux")]
fn rename_no_replace(source: &Path, target: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source path contains NUL"))?;
    let target = CString::new(target.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "target path contains NUL"))?;
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            source.as_ptr(),
            libc::AT_FDCWD,
            target.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "linux")]
fn rename_exchange(first: &Path, second: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let first = CString::new(first.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "first path contains NUL"))?;
    let second = CString::new(second.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "second path contains NUL"))?;
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            first.as_ptr(),
            libc::AT_FDCWD,
            second.as_ptr(),
            libc::RENAME_EXCHANGE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn rename_no_replace(_source: &Path, _target: &Path) -> Result<()> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Atomic no-replace PATH projection is supported only on macOS and Linux.",
    ))
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn rename_exchange(_first: &Path, _second: &Path) -> Result<()> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Atomic PATH projection exchange is supported only on macOS and Linux.",
    ))
}

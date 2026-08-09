fn revalidate_service_runtime(
    config: &KastConfig,
    expected: &ServiceOwnedRuntime,
) -> Result<ServiceOwnedRuntime> {
    let RuntimeOwnershipSnapshot::ServiceOwned(current) =
        reconcile_runtime_ownership(config, &expected.workspace_root)?
    else {
        return Err(ownership_changed(
            "Registered runtime ownership changed before stop.",
        ));
    };
    if current.registration.receipt_sha256 != expected.registration.receipt_sha256
        || current.process != expected.process
        || current
            .descriptor
            .as_ref()
            .map(|value| (&value.id, &value.descriptor))
            != expected
                .descriptor
                .as_ref()
                .map(|value| (&value.id, &value.descriptor))
        || current.socket != expected.socket
        || current.manager != expected.manager
    {
        return Err(ownership_changed(
            "Registered runtime evidence changed before stop.",
        ));
    }
    Ok(*current)
}

fn revalidate_legacy_runtime(
    config: &KastConfig,
    expected: &LegacyOwnedRuntime,
) -> Result<LegacyOwnedRuntime> {
    let RuntimeOwnershipSnapshot::LegacyOwned(current) =
        reconcile_runtime_ownership(config, &expected.workspace_root)?
    else {
        return Err(ownership_changed(
            "Legacy runtime ownership changed before stop.",
        ));
    };
    if current.process != expected.process
        || current.descriptor.id != expected.descriptor.id
        || current.descriptor.descriptor != expected.descriptor.descriptor
        || current.socket != expected.socket
    {
        return Err(ownership_changed(
            "Legacy runtime evidence changed before stop.",
        ));
    }
    Ok(*current)
}

fn signal_exact_observed_process(
    expected: &super::process::ObservedProcess,
    socket: &SocketObservation,
    force: bool,
) -> Result<()> {
    let current = observe_process(expected.identity.pid)?.ok_or_else(|| {
        ownership_changed("Runtime process exited before stop could revalidate it.")
    })?;
    if current != *expected {
        return Err(ownership_changed(
            "Runtime process identity or command changed before stop.",
        ));
    }
    verify_socket_snapshot(socket, expected.identity.owner_uid)?;
    signal_process(&expected.identity, force)
}

#[derive(Clone, Copy)]
enum ExpectedStopTerminal<'a> {
    Service(&'a ServiceOwnedRuntime),
    Legacy(&'a LegacyOwnedRuntime),
}

impl ExpectedStopTerminal<'_> {
    fn workspace_root(&self) -> &Path {
        match self {
            Self::Service(owned) => &owned.workspace_root,
            Self::Legacy(owned) => &owned.workspace_root,
        }
    }

    fn process(&self) -> &super::process::ObservedProcess {
        match self {
            Self::Service(owned) => &owned.process,
            Self::Legacy(owned) => &owned.process,
        }
    }

    fn socket(&self) -> &SocketObservation {
        match self {
            Self::Service(owned) => &owned.socket,
            Self::Legacy(owned) => &owned.socket,
        }
    }

    fn graceful_timeout(&self) -> Duration {
        match self {
            Self::Service(_) => Duration::from_secs(10),
            Self::Legacy(_) => Duration::from_secs(5),
        }
    }

    fn stop_failed(&self) -> CliError {
        let message = match self {
            Self::Service(_) => "The exact registered runtime did not stop.",
            Self::Legacy(_) => "The exact legacy Kast runtime did not stop.",
        };
        CliError::new("RUNTIME_STOP_FAILED", message)
    }
}

struct ProvenGoneRuntime<'a> {
    expected: ExpectedStopTerminal<'a>,
    forced: bool,
}

fn prove_runtime_gone(expected: ExpectedStopTerminal<'_>) -> Result<ProvenGoneRuntime<'_>> {
    signal_exact_observed_process(expected.process(), expected.socket(), false)?;
    let mut forced = false;
    if !wait_until_gone(
        &expected.process().identity,
        expected.graceful_timeout(),
    )? {
        signal_exact_observed_process(expected.process(), expected.socket(), true)?;
        forced = true;
        if !wait_until_gone(&expected.process().identity, Duration::from_secs(5))? {
            return Err(expected.stop_failed());
        }
    }
    Ok(ProvenGoneRuntime { expected, forced })
}

enum ObservedStopTerminalState {
    Absent,
    Service(Box<DeadServiceRuntime>),
    Legacy(Box<DeadLegacyRuntime>),
}

impl ObservedStopTerminalState {
    fn admit(
        gone: &ProvenGoneRuntime<'_>,
        snapshot: RuntimeOwnershipSnapshot,
    ) -> std::result::Result<Self, StopTerminalFailure> {
        match (gone.expected, snapshot) {
            (expected, RuntimeOwnershipSnapshot::Absent(absent))
                if absent.workspace_root == expected.workspace_root() =>
            {
                Ok(Self::Absent)
            }
            (ExpectedStopTerminal::Service(expected), RuntimeOwnershipSnapshot::ProvenDead(dead)) => {
                admit_dead_service(expected, dead).map(|dead| Self::Service(Box::new(dead)))
            }
            (ExpectedStopTerminal::Legacy(expected), RuntimeOwnershipSnapshot::ProvenDead(dead)) => {
                admit_dead_legacy(expected, dead).map(|dead| Self::Legacy(Box::new(dead)))
            }
            (_, RuntimeOwnershipSnapshot::ServiceOwned(_)) => {
                Err(StopTerminalFailure::ServiceStillLive)
            }
            (_, RuntimeOwnershipSnapshot::LegacyOwned(_)) => {
                Err(StopTerminalFailure::LegacyStillLive)
            }
            (_, RuntimeOwnershipSnapshot::Conflict(conflict)) => Err(
                StopTerminalFailure::Conflict(conflict.runtime_instance_ids),
            ),
            (_, RuntimeOwnershipSnapshot::Ambiguous(ambiguity)) => {
                Err(StopTerminalFailure::Ambiguous(ambiguity.reason))
            }
            _ => Err(StopTerminalFailure::OwnershipChanged),
        }
    }

    fn cleanup(self, config: &KastConfig) -> Result<()> {
        match self {
            Self::Absent => Ok(()),
            Self::Service(dead) => cleanup_dead_registration(config, &dead),
            Self::Legacy(dead) => cleanup_dead_legacy(config, &dead),
        }
    }
}

enum StopTerminalFailure {
    ServiceStillLive,
    LegacyStillLive,
    OwnershipChanged,
    Conflict(Vec<String>),
    Ambiguous(String),
}

impl From<StopTerminalFailure> for CliError {
    fn from(failure: StopTerminalFailure) -> Self {
        let message = match failure {
            StopTerminalFailure::ServiceStillLive => {
                "The exact registered runtime remained live after stop.".to_string()
            }
            StopTerminalFailure::LegacyStillLive => {
                "The exact legacy runtime remained live after stop.".to_string()
            }
            StopTerminalFailure::OwnershipChanged => {
                "Runtime ownership changed before terminal cleanup.".to_string()
            }
            StopTerminalFailure::Conflict(ids) => format!(
                "Runtime ownership became conflicting before terminal cleanup: {}.",
                ids.join(", ")
            ),
            StopTerminalFailure::Ambiguous(reason) => format!(
                "Runtime ownership became ambiguous before terminal cleanup: {reason}"
            ),
        };
        CliError::new("RUNTIME_OWNERSHIP_CHANGED", message)
    }
}

fn observe_stop_terminal_state(
    config: &KastConfig,
    gone: &ProvenGoneRuntime<'_>,
) -> Result<ObservedStopTerminalState> {
    let snapshot = reconcile_runtime_ownership(config, gone.expected.workspace_root())?;
    ObservedStopTerminalState::admit(gone, snapshot).map_err(Into::into)
}

fn admit_dead_service(
    expected: &ServiceOwnedRuntime,
    mut dead: super::ownership::ProvenDeadRuntimeOwnership,
) -> std::result::Result<DeadServiceRuntime, StopTerminalFailure> {
    if !dead.legacy.is_empty() || dead.services.len() != 1 {
        return Err(StopTerminalFailure::OwnershipChanged);
    }
    let current = dead.services.pop().expect("one dead service was proven");
    let descriptor_matches = current.descriptor.as_ref().is_none_or(|current| {
        expected.descriptor.as_ref().is_some_and(|expected| {
            current.id == expected.id && current.descriptor == expected.descriptor
        })
    });
    let process_matches = current
        .process_claim
        .as_ref()
        .is_none_or(|claim| claim.process == expected.process.identity);
    let active_matches = current.active.as_ref().is_none_or(|active| {
        active.runtime_instance_id == expected.registration.receipt.runtime_instance_id
            && active.receipt_sha256 == expected.registration.receipt_sha256
    });
    if current.registration.receipt_sha256 != expected.registration.receipt_sha256
        || current.registration.receipt != expected.registration.receipt
        || current.registration.launch != expected.registration.launch
        || !descriptor_matches
        || !process_matches
        || !active_matches
        || !terminal_socket_matches(
            &current.socket,
            &expected.socket,
            Path::new(&expected.registration.launch.socket_path),
        )
    {
        return Err(StopTerminalFailure::OwnershipChanged);
    }
    Ok(current)
}

fn admit_dead_legacy(
    expected: &LegacyOwnedRuntime,
    mut dead: super::ownership::ProvenDeadRuntimeOwnership,
) -> std::result::Result<DeadLegacyRuntime, StopTerminalFailure> {
    if !dead.services.is_empty() || dead.legacy.len() != 1 {
        return Err(StopTerminalFailure::OwnershipChanged);
    }
    let current = dead.legacy.pop().expect("one dead legacy runtime was proven");
    if current.descriptor.id != expected.descriptor.id
        || current.descriptor.descriptor != expected.descriptor.descriptor
        || current.owner_uid != expected.process.identity.owner_uid
        || !terminal_socket_matches(
            &current.socket,
            &expected.socket,
            Path::new(&expected.descriptor.descriptor.socket_path),
        )
    {
        return Err(StopTerminalFailure::OwnershipChanged);
    }
    Ok(current)
}

fn terminal_socket_matches(
    current: &SocketObservation,
    expected: &SocketObservation,
    expected_path: &Path,
) -> bool {
    match current {
        SocketObservation::Absent { path } => path == expected_path,
        _ => current == expected,
    }
}

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

use super::ownership::{DeadLegacyRuntime, DeadServiceRuntime, SocketObservation};
use super::process::observe_process;
use super::registration::{
    ValidatedServiceRegistration, validate_entrypoint_registration, write_process_claim,
};
use super::*;

pub(super) fn cleanup_dead_services(
    config: &KastConfig,
    services: &[DeadServiceRuntime],
) -> Result<()> {
    let mut ordered = services.iter().collect::<Vec<_>>();
    ordered.sort_by_key(|runtime| dead_service_is_active(runtime));
    for runtime in ordered {
        cleanup_dead_registration(config, runtime)?;
    }
    Ok(())
}

pub(super) fn cleanup_proven_dead(
    config: &KastConfig,
    dead: &super::ownership::ProvenDeadRuntimeOwnership,
) -> Result<()> {
    cleanup_dead_services(config, &dead.services)?;
    for runtime in &dead.legacy {
        cleanup_dead_legacy(config, runtime)?;
    }
    Ok(())
}

fn dead_service_is_active(runtime: &DeadServiceRuntime) -> bool {
    runtime.active.as_ref().is_some_and(|active| {
        active.runtime_instance_id == runtime.registration.receipt.runtime_instance_id
    })
}

include!("ownership/cleanup.rs");
include!("ownership/entrypoint.rs");

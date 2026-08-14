use std::marker::PhantomData;
use std::path::{Path, PathBuf};

mod capabilities {
    include!("lifecycle_typestate/capabilities.rs");
}

pub(crate) use capabilities::{
    CompilerCapability, CurrentCapability, GraphCapability, MutationCapability,
    PersistedCapability, PublishedCapabilityFreshness, ReferenceCapability, ReferenceReady,
    RequiredCapability, SourceCapability, WorkspaceFilesCapability,
};

#[derive(Debug, Clone, Copy)]
pub(crate) struct Demand<C: RequiredCapability>(PhantomData<C>);

impl<C: RequiredCapability> Demand<C> {
    pub(crate) fn new() -> Self {
        Self(PhantomData)
    }

    pub(crate) fn admit(self, root: CanonicalWorkspaceRoot) -> WorkspaceAdmitted<C> {
        WorkspaceAdmitted {
            root,
            capability: PhantomData,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct CanonicalWorkspaceRoot(PathBuf);

impl CanonicalWorkspaceRoot {
    pub(crate) fn from_canonical(path: PathBuf) -> Self {
        debug_assert!(path.is_absolute());
        Self(path)
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

#[derive(Debug, Clone)]
pub(crate) struct WorkspaceAdmitted<C: RequiredCapability> {
    root: CanonicalWorkspaceRoot,
    capability: PhantomData<C>,
}

impl<C: RequiredCapability> WorkspaceAdmitted<C> {
    pub(crate) fn observe_absent(self) -> AbsentOwnership<C> {
        AbsentOwnership(self)
    }

    pub(crate) fn observe_proven_dead(self) -> ProvenDeadOwnership<C> {
        ProvenDeadOwnership(self)
    }

    pub(crate) fn observe_exact(self, identity: RuntimeEpochIdentity) -> ExactOwnedRuntime<C> {
        ExactOwnedRuntime {
            admitted: self,
            identity,
        }
    }
}

#[derive(Debug)]
pub(crate) struct AbsentOwnership<C: RequiredCapability>(WorkspaceAdmitted<C>);

#[derive(Debug)]
pub(crate) struct ProvenDeadOwnership<C: RequiredCapability>(WorkspaceAdmitted<C>);

#[derive(Debug)]
pub(crate) struct ExactOwnedRuntime<C: RequiredCapability> {
    admitted: WorkspaceAdmitted<C>,
    identity: RuntimeEpochIdentity,
}

#[derive(Debug)]
pub(crate) struct LaunchPermit<C: RequiredCapability>(WorkspaceAdmitted<C>);

impl<C: RequiredCapability> AbsentOwnership<C> {
    pub(crate) fn permit_launch(self) -> LaunchPermit<C> {
        LaunchPermit(self.0)
    }
}

impl<C: RequiredCapability> ProvenDeadOwnership<C> {
    pub(crate) fn permit_single_replacement(self) -> LaunchPermit<C> {
        LaunchPermit(self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct RuntimeEpochId(String);

impl RuntimeEpochId {
    pub(crate) fn from_validated(value: String) -> Self {
        debug_assert!(!value.is_empty());
        Self(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct RuntimeEpochIdentity {
    runtime_instance_id: RuntimeEpochId,
    process_id: u64,
    process_start_epoch_millis: u64,
    socket_device: u64,
    socket_inode: u64,
}

impl RuntimeEpochIdentity {
    pub(crate) fn from_validated_parts(
        runtime_instance_id: RuntimeEpochId,
        process_id: u64,
        process_start_epoch_millis: u64,
        socket_device: u64,
        socket_inode: u64,
    ) -> Self {
        Self {
            runtime_instance_id,
            process_id,
            process_start_epoch_millis,
            socket_device,
            socket_inode,
        }
    }

    pub(crate) fn epoch_id(&self) -> &RuntimeEpochId {
        &self.runtime_instance_id
    }
}

#[derive(Debug)]
pub(crate) struct StartingEpoch<C: RequiredCapability> {
    admitted: WorkspaceAdmitted<C>,
    epoch_id: RuntimeEpochId,
}

impl<C: RequiredCapability> LaunchPermit<C> {
    pub(crate) fn starting(self, epoch_id: RuntimeEpochId) -> StartingEpoch<C> {
        StartingEpoch {
            admitted: self.0,
            epoch_id,
        }
    }
}

#[derive(Debug)]
pub(crate) struct RevalidatedEpoch<C: RequiredCapability>(ExactOwnedRuntime<C>);

impl<C: RequiredCapability> ExactOwnedRuntime<C> {
    pub(crate) fn revalidated(self) -> RevalidatedEpoch<C> {
        RevalidatedEpoch(self)
    }
}

#[derive(Debug, Clone)]
pub(crate) struct RuntimeAvailable<C: RequiredCapability> {
    root: CanonicalWorkspaceRoot,
    identity: RuntimeEpochIdentity,
    capability: PhantomData<C>,
}

impl<C: RequiredCapability> StartingEpoch<C> {
    pub(crate) fn available(
        self,
        identity: RuntimeEpochIdentity,
    ) -> Result<RuntimeAvailable<C>, LifecycleBlocker> {
        if &self.epoch_id != identity.epoch_id() {
            return Err(LifecycleBlocker::IdentityChanged);
        }
        Ok(RuntimeAvailable {
            root: self.admitted.root,
            identity,
            capability: PhantomData,
        })
    }
}

impl<C: RequiredCapability> RevalidatedEpoch<C> {
    pub(crate) fn available(self) -> RuntimeAvailable<C> {
        RuntimeAvailable {
            root: self.0.admitted.root,
            identity: self.0.identity,
            capability: PhantomData,
        }
    }
}

impl<C: RequiredCapability> RuntimeAvailable<C> {
    pub(crate) fn root(&self) -> &CanonicalWorkspaceRoot {
        &self.root
    }

    pub(crate) fn identity(&self) -> &RuntimeEpochIdentity {
        &self.identity
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LifecycleBlocker {
    UnsupportedRoot,
    OwnershipConflict,
    OwnershipAmbiguous,
    IdentityChanged,
    ReplacementFailed,
    CapabilityUnavailable,
}

#[cfg(test)]
mod tests {
    include!("lifecycle_typestate/tests.rs");
}

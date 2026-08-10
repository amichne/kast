use std::marker::PhantomData;
use std::path::{Path, PathBuf};

pub(crate) trait RequiredCapability:
    private::Sealed + std::fmt::Debug + Clone + Copy
{
    type Ready: std::fmt::Debug + Clone;
    const REQUIREMENT: CapabilityRequirement;

    fn finish(source: SourceReady<Self>) -> Self::Ready;
    fn source(ready: &Self::Ready) -> &SourceReady<Self>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CapabilityRequirement {
    Source,
    Reference,
    Graph,
}

#[derive(Debug, Clone, Copy)]
pub(crate) enum SourceCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum ReferenceCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum GraphCapability {}

impl RequiredCapability for SourceCapability {
    type Ready = SourceReady<Self>;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Source;

    fn finish(source: SourceReady<Self>) -> Self::Ready {
        source
    }

    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready
    }
}

impl RequiredCapability for ReferenceCapability {
    type Ready = ReferenceReady<Self>;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Reference;

    fn finish(source: SourceReady<Self>) -> Self::Ready {
        source.reference_ready()
    }

    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready.source()
    }
}

impl RequiredCapability for GraphCapability {
    type Ready = GraphReady<Self>;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Graph;

    fn finish(source: SourceReady<Self>) -> Self::Ready {
        source.graph_ready()
    }

    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready.source()
    }
}

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

    pub(crate) fn model_ready(self) -> ModelReady<C> {
        ModelReady(self)
    }
}

#[derive(Debug)]
pub(crate) struct ModelReady<C: RequiredCapability>(RuntimeAvailable<C>);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceRevision(u64);

impl SourceRevision {
    pub(crate) fn positive(value: u64) -> Result<Self, LifecycleBlocker> {
        if value == 0 {
            Err(LifecycleBlocker::CapabilityUnavailable)
        } else {
            Ok(Self(value))
        }
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone)]
pub(crate) struct SourceReady<C: RequiredCapability> {
    runtime: RuntimeAvailable<C>,
    revision: SourceRevision,
}

impl<C: RequiredCapability> ModelReady<C> {
    pub(crate) fn source_ready(self, revision: SourceRevision) -> SourceReady<C> {
        SourceReady {
            runtime: self.0,
            revision,
        }
    }
}

impl<C: RequiredCapability> SourceReady<C> {
    pub(crate) fn runtime(&self) -> &RuntimeAvailable<C> {
        &self.runtime
    }

    pub(crate) fn revision(&self) -> SourceRevision {
        self.revision
    }

    pub(crate) fn reference_ready(self) -> ReferenceReady<C> {
        ReferenceReady(self)
    }

    pub(crate) fn graph_ready(self) -> GraphReady<C> {
        GraphReady(self)
    }
}

#[derive(Debug, Clone)]
pub(crate) struct ReferenceReady<C: RequiredCapability>(SourceReady<C>);

impl<C: RequiredCapability> ReferenceReady<C> {
    pub(crate) fn source(&self) -> &SourceReady<C> {
        &self.0
    }
}

#[derive(Debug, Clone)]
pub(crate) struct GraphReady<C: RequiredCapability>(SourceReady<C>);

impl<C: RequiredCapability> GraphReady<C> {
    pub(crate) fn source(&self) -> &SourceReady<C> {
        &self.0
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

mod private {
    pub trait Sealed {}
    impl Sealed for super::SourceCapability {}
    impl Sealed for super::ReferenceCapability {}
    impl Sealed for super::GraphCapability {}
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity(id: &str) -> RuntimeEpochIdentity {
        RuntimeEpochIdentity::from_validated_parts(
            RuntimeEpochId::from_validated(id.to_string()),
            7,
            11,
            13,
            17,
        )
    }

    #[test]
    fn legal_reuse_and_independent_lane_transitions_compile() {
        let root = CanonicalWorkspaceRoot::from_canonical(PathBuf::from("/repo"));
        let exact = Demand::<SourceCapability>::new()
            .admit(root)
            .observe_exact(identity("epoch-1"));
        let source = exact
            .revalidated()
            .available()
            .model_ready()
            .source_ready(SourceRevision::positive(1).expect("positive revision"));
        let _reference = source.clone().reference_ready();
        let _graph = source.graph_ready();
    }

    #[test]
    fn launch_requires_a_matching_immutable_epoch() {
        let root = CanonicalWorkspaceRoot::from_canonical(PathBuf::from("/repo"));
        let starting = Demand::<GraphCapability>::new()
            .admit(root)
            .observe_absent()
            .permit_launch()
            .starting(RuntimeEpochId::from_validated("epoch-2".to_string()));

        assert!(matches!(
            starting.available(identity("other")),
            Err(LifecycleBlocker::IdentityChanged)
        ));
    }
}

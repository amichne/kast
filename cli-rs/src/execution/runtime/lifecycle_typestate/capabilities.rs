use super::{LifecycleBlocker, RuntimeAvailable};
use crate::published_workspace::{
    PublishedGraphEvidence, PublishedWorkspaceGenerationManifest,
};
use crate::runtime::{
    CurrentCapabilityLaneReadiness, RetainedCapabilityLaneFallback,
    RetainedCapabilityLaneFreshness, RetainedCapabilityLaneReadiness,
    RetainedWorkspaceGenerationStatus, RuntimeStatusResponse,
};

pub(crate) trait RequiredCapability:
    private::Sealed + std::fmt::Debug + Clone + Copy
{
    type Ready: std::fmt::Debug + Clone;
    type Evidence: std::fmt::Debug + Clone + PartialEq + Eq;
    const REQUIREMENT: CapabilityRequirement;
    const REVALIDATE_AFTER_RPC: bool = true;

    fn admit(status: &RuntimeStatusResponse) -> Result<Self::Evidence, LifecycleBlocker>;
    fn finish(runtime: RuntimeAvailable<Self>, evidence: Self::Evidence) -> Self::Ready;
    fn stamp(ready: &Self::Ready) -> CapabilityStamp;
}

pub(crate) trait CurrentCapability:
    RequiredCapability<Evidence = CurrentCapabilityEvidence>
{
    fn current(ready: &Self::Ready) -> &CurrentLaneReady<Self>;
}

pub(crate) trait PersistedCapability:
    RequiredCapability<Evidence = PublishedCapabilityEvidence>
{
    fn source(ready: &Self::Ready) -> &SourceReady<Self>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CapabilityRequirement {
    Compiler,
    WorkspaceFiles,
    Source,
    Reference,
    Graph,
    Mutation,
}

#[derive(Debug, Clone, Copy)]
pub(crate) enum CompilerCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum WorkspaceFilesCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum SourceCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum ReferenceCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum GraphCapability {}

#[derive(Debug, Clone, Copy)]
pub(crate) enum MutationCapability {}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct CurrentCapabilityRevision(u64);

impl CurrentCapabilityRevision {
    fn positive(value: u64) -> Result<Self, LifecycleBlocker> {
        (value > 0)
            .then_some(Self(value))
            .ok_or(LifecycleBlocker::CapabilityUnavailable)
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceRevision(u64);

impl SourceRevision {
    fn positive(value: u64) -> Result<Self, LifecycleBlocker> {
        (value > 0)
            .then_some(Self(value))
            .ok_or(LifecycleBlocker::CapabilityUnavailable)
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PublishedCapabilityFreshness {
    Current,
    Previous,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CurrentCapabilityEvidence {
    revision: CurrentCapabilityRevision,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct PublishedCapabilityEvidence {
    publication: PublishedWorkspaceGenerationManifest,
    lane_revision: u64,
    freshness: PublishedCapabilityFreshness,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CapabilityStamp {
    Current {
        requirement: CapabilityRequirement,
        revision: u64,
    },
    Published {
        requirement: CapabilityRequirement,
        publication: PublishedWorkspaceGenerationManifest,
        lane_revision: u64,
        freshness: PublishedCapabilityFreshness,
    },
}

#[derive(Debug, Clone)]
pub(crate) struct CurrentLaneReady<C: CurrentCapability> {
    runtime: RuntimeAvailable<C>,
    revision: CurrentCapabilityRevision,
}

impl<C: CurrentCapability> CurrentLaneReady<C> {
    pub(crate) fn runtime(&self) -> &RuntimeAvailable<C> {
        &self.runtime
    }

    pub(crate) fn revision(&self) -> u64 {
        self.revision.value()
    }
}

#[derive(Debug, Clone)]
pub(crate) struct SourceReady<C: PersistedCapability> {
    runtime: RuntimeAvailable<C>,
    source_revision: SourceRevision,
    lane_revision: u64,
    publication: PublishedWorkspaceGenerationManifest,
    freshness: PublishedCapabilityFreshness,
}

impl<C: PersistedCapability> SourceReady<C> {
    pub(crate) fn runtime(&self) -> &RuntimeAvailable<C> {
        &self.runtime
    }

    pub(crate) fn revision(&self) -> SourceRevision {
        self.source_revision
    }

    pub(crate) fn lane_revision(&self) -> u64 {
        self.lane_revision
    }

    pub(crate) fn publication(&self) -> &PublishedWorkspaceGenerationManifest {
        &self.publication
    }

    pub(crate) fn freshness(&self) -> PublishedCapabilityFreshness {
        self.freshness
    }

    fn reference_ready(self) -> ReferenceReady<C> {
        ReferenceReady(self)
    }

    fn graph_ready(self) -> GraphReady<C> {
        GraphReady(self)
    }
}

#[derive(Debug, Clone)]
pub(crate) struct ReferenceReady<C: PersistedCapability>(SourceReady<C>);

impl<C: PersistedCapability> ReferenceReady<C> {
    pub(crate) fn source(&self) -> &SourceReady<C> {
        &self.0
    }
}

#[derive(Debug, Clone)]
pub(crate) struct GraphReady<C: PersistedCapability>(SourceReady<C>);

impl<C: PersistedCapability> GraphReady<C> {
    pub(crate) fn source(&self) -> &SourceReady<C> {
        &self.0
    }
}

macro_rules! current_capability {
    ($capability:ty, $requirement:expr, $lane:ident, $post:expr) => {
        impl RequiredCapability for $capability {
            type Ready = CurrentLaneReady<Self>;
            type Evidence = CurrentCapabilityEvidence;
            const REQUIREMENT: CapabilityRequirement = $requirement;
            const REVALIDATE_AFTER_RPC: bool = $post;

            fn admit(status: &RuntimeStatusResponse) -> Result<Self::Evidence, LifecycleBlocker> {
                current_evidence(&status.readiness.$lane)
            }

            fn finish(runtime: RuntimeAvailable<Self>, evidence: Self::Evidence) -> Self::Ready {
                CurrentLaneReady {
                    runtime,
                    revision: evidence.revision,
                }
            }

            fn stamp(ready: &Self::Ready) -> CapabilityStamp {
                CapabilityStamp::Current {
                    requirement: Self::REQUIREMENT,
                    revision: ready.revision(),
                }
            }
        }

        impl CurrentCapability for $capability {
            fn current(ready: &Self::Ready) -> &CurrentLaneReady<Self> {
                ready
            }
        }
    };
}

current_capability!(
    CompilerCapability,
    CapabilityRequirement::Compiler,
    compiler,
    true
);
current_capability!(
    WorkspaceFilesCapability,
    CapabilityRequirement::WorkspaceFiles,
    workspace_files,
    true
);
current_capability!(
    MutationCapability,
    CapabilityRequirement::Mutation,
    mutation,
    false
);

impl RequiredCapability for SourceCapability {
    type Ready = SourceReady<Self>;
    type Evidence = PublishedCapabilityEvidence;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Source;

    fn admit(status: &RuntimeStatusResponse) -> Result<Self::Evidence, LifecycleBlocker> {
        published_evidence(status, &status.readiness.source_index, source_revision)
    }

    fn finish(runtime: RuntimeAvailable<Self>, evidence: Self::Evidence) -> Self::Ready {
        source_ready(runtime, evidence)
    }

    fn stamp(ready: &Self::Ready) -> CapabilityStamp {
        published_stamp(Self::REQUIREMENT, ready)
    }
}

impl PersistedCapability for SourceCapability {
    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready
    }
}

impl RequiredCapability for ReferenceCapability {
    type Ready = ReferenceReady<Self>;
    type Evidence = PublishedCapabilityEvidence;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Reference;

    fn admit(status: &RuntimeStatusResponse) -> Result<Self::Evidence, LifecycleBlocker> {
        published_evidence(status, &status.readiness.references, reference_revision)
    }

    fn finish(runtime: RuntimeAvailable<Self>, evidence: Self::Evidence) -> Self::Ready {
        source_ready(runtime, evidence).reference_ready()
    }

    fn stamp(ready: &Self::Ready) -> CapabilityStamp {
        published_stamp(Self::REQUIREMENT, ready.source())
    }
}

impl PersistedCapability for ReferenceCapability {
    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready.source()
    }
}

impl RequiredCapability for GraphCapability {
    type Ready = GraphReady<Self>;
    type Evidence = PublishedCapabilityEvidence;
    const REQUIREMENT: CapabilityRequirement = CapabilityRequirement::Graph;

    fn admit(status: &RuntimeStatusResponse) -> Result<Self::Evidence, LifecycleBlocker> {
        published_evidence(status, &status.readiness.semantic_graph, graph_revision)
    }

    fn finish(runtime: RuntimeAvailable<Self>, evidence: Self::Evidence) -> Self::Ready {
        source_ready(runtime, evidence).graph_ready()
    }

    fn stamp(ready: &Self::Ready) -> CapabilityStamp {
        published_stamp(Self::REQUIREMENT, ready.source())
    }
}

impl PersistedCapability for GraphCapability {
    fn source(ready: &Self::Ready) -> &SourceReady<Self> {
        ready.source()
    }
}

include!("capability_admission.rs");

mod private {
    pub trait Sealed {}
    impl Sealed for super::CompilerCapability {}
    impl Sealed for super::WorkspaceFilesCapability {}
    impl Sealed for super::SourceCapability {}
    impl Sealed for super::ReferenceCapability {}
    impl Sealed for super::GraphCapability {}
    impl Sealed for super::MutationCapability {}
}

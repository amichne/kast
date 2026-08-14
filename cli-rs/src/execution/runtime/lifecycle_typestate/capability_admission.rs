fn current_evidence(
    lane: &CurrentCapabilityLaneReadiness,
) -> Result<CurrentCapabilityEvidence, LifecycleBlocker> {
    let CurrentCapabilityLaneReadiness::Available { evidence } = lane else {
        return Err(LifecycleBlocker::CapabilityUnavailable);
    };
    Ok(CurrentCapabilityEvidence {
        revision: CurrentCapabilityRevision::positive(evidence.revision())?,
    })
}

fn published_evidence(
    status: &RuntimeStatusResponse,
    lane: &RetainedCapabilityLaneReadiness,
    publication_revision: fn(&PublishedWorkspaceGenerationManifest) -> Option<u64>,
) -> Result<PublishedCapabilityEvidence, LifecycleBlocker> {
    let (lane_revision, freshness, publication) = match lane {
        RetainedCapabilityLaneReadiness::Available { evidence } => {
            let freshness = match evidence.freshness() {
                RetainedCapabilityLaneFreshness::Current => PublishedCapabilityFreshness::Current,
                RetainedCapabilityLaneFreshness::Previous => {
                    PublishedCapabilityFreshness::Previous
                }
            };
            (
                evidence.revision(),
                freshness,
                publication_for(status, freshness)?,
            )
        }
        RetainedCapabilityLaneReadiness::Building {
            fallback: RetainedCapabilityLaneFallback::Previous { evidence },
            ..
        } => (
            evidence.revision(),
            PublishedCapabilityFreshness::Previous,
            publication_for(status, PublishedCapabilityFreshness::Previous)?,
        ),
        RetainedCapabilityLaneReadiness::Building {
            fallback: RetainedCapabilityLaneFallback::None,
            ..
        }
        | RetainedCapabilityLaneReadiness::Blocked { .. } => {
            return Err(LifecycleBlocker::CapabilityUnavailable);
        }
    };
    if publication.source_revision == 0
        || publication_revision(publication) != Some(lane_revision)
    {
        return Err(LifecycleBlocker::CapabilityUnavailable);
    }
    Ok(PublishedCapabilityEvidence {
        publication: publication.clone(),
        lane_revision,
        freshness,
    })
}

fn publication_for(
    status: &RuntimeStatusResponse,
    freshness: PublishedCapabilityFreshness,
) -> Result<&PublishedWorkspaceGenerationManifest, LifecycleBlocker> {
    match freshness {
        PublishedCapabilityFreshness::Current => status
            .published_workspace_generation
            .as_ref()
            .ok_or(LifecycleBlocker::CapabilityUnavailable),
        PublishedCapabilityFreshness::Previous => match &status.retained_workspace_generation {
            RetainedWorkspaceGenerationStatus::Previous { publication } => Ok(publication),
            RetainedWorkspaceGenerationStatus::None => {
                Err(LifecycleBlocker::CapabilityUnavailable)
            }
        },
    }
}

fn source_revision(publication: &PublishedWorkspaceGenerationManifest) -> Option<u64> {
    Some(publication.source_revision)
}

fn reference_revision(publication: &PublishedWorkspaceGenerationManifest) -> Option<u64> {
    Some(publication.reference_revision)
}

fn graph_revision(publication: &PublishedWorkspaceGenerationManifest) -> Option<u64> {
    match publication.graph_publication {
        PublishedGraphEvidence::Ready { revision } => Some(revision),
        PublishedGraphEvidence::Blocked { .. } => None,
    }
}

fn source_ready<C: PersistedCapability>(
    runtime: RuntimeAvailable<C>,
    evidence: PublishedCapabilityEvidence,
) -> SourceReady<C> {
    SourceReady {
        runtime,
        source_revision: SourceRevision::positive(evidence.publication.source_revision)
            .expect("published evidence admission established a positive source revision"),
        lane_revision: evidence.lane_revision,
        publication: evidence.publication,
        freshness: evidence.freshness,
    }
}

fn published_stamp<C: PersistedCapability>(
    requirement: CapabilityRequirement,
    ready: &SourceReady<C>,
) -> CapabilityStamp {
    CapabilityStamp::Published {
        requirement,
        publication: ready.publication.clone(),
        lane_revision: ready.lane_revision,
        freshness: ready.freshness,
    }
}

use super::*;
use crate::runtime::{
    CurrentCapabilityLaneReadiness, ReferenceCoverageState,
    RetainedCapabilityLaneReadiness, RetainedWorkspaceGenerationStatus, RuntimeReadiness,
    RuntimeState, RuntimeStatusResponse,
};
use serde_json::json;

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

#[test]
fn current_lane_wire_rejects_previous_freshness() {
    let decoded = serde_json::from_value::<CurrentCapabilityLaneReadiness>(json!({
        "type": "AVAILABLE",
        "evidence": {"revision": 3, "freshness": "PREVIOUS"}
    }));

    assert!(decoded.is_err());
}

#[test]
fn retained_source_lane_admits_explicit_previous_publication() {
    let publication = crate::published_workspace::PublishedWorkspaceGenerationManifest {
        generation: 2,
        identity: "previous-workspace".to_string(),
        source_index_generation: 2,
        source_revision: 11,
        reference_revision: 7,
        graph_publication: crate::published_workspace::PublishedGraphEvidence::Ready {
            revision: 5,
        },
        source_index_schema_version: crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION,
        database_file: "source-index.db".to_string(),
        published_at_epoch_millis: 17,
        repository_overlay_file: None,
    };
    let mut readiness = RuntimeReadiness::ready();
    readiness.source_index = serde_json::from_value::<RetainedCapabilityLaneReadiness>(json!({
        "type": "BUILDING",
        "progress": {},
        "fallback": {
            "type": "PREVIOUS",
            "evidence": {"revision": 11, "freshness": "PREVIOUS"}
        }
    }))
    .expect("previous retained lane");
    let status = RuntimeStatusResponse {
        state: RuntimeState::Ready,
        backend_name: "indexer".to_string(),
        backend_version: "test".to_string(),
        workspace_root: "/repo".to_string(),
        message: None,
        warnings: vec![],
        source_module_names: vec!["root".to_string()],
        dependent_module_names_by_source_module_name: serde_json::Map::new(),
        reference_coverage_state: ReferenceCoverageState::Qualified,
        reference_coverage_limitations: vec![],
        published_workspace_generation: None,
        retained_workspace_generation: RetainedWorkspaceGenerationStatus::Previous {
            publication,
        },
        readiness,
        schema_version: crate::SCHEMA_VERSION,
    };

    let evidence = SourceCapability::admit(&status).expect("previous source evidence");
    let runtime = Demand::<SourceCapability>::new()
        .admit(CanonicalWorkspaceRoot::from_canonical(PathBuf::from("/repo")))
        .observe_exact(identity("epoch-previous"))
        .revalidated()
        .available();
    let ready = SourceCapability::finish(runtime, evidence);

    assert_eq!(ready.freshness(), PublishedCapabilityFreshness::Previous);
    assert_eq!(ready.lane_revision(), 11);
}

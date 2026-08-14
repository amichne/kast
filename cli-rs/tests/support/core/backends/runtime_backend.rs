use super::*;

pub(crate) fn available_current_lane(revision: u64) -> serde_json::Value {
    serde_json::json!({
        "type": "AVAILABLE",
        "evidence": {"revision": revision, "freshness": "CURRENT"}
    })
}

pub(crate) fn available_retained_lane(revision: u64) -> serde_json::Value {
    serde_json::json!({
        "type": "AVAILABLE",
        "evidence": {"revision": revision, "freshness": "CURRENT"}
    })
}

pub(crate) fn blocked_current_lane() -> serde_json::Value {
    serde_json::json!({"type": "BLOCKED", "blocker": "CAPABILITY_UNAVAILABLE"})
}

pub(crate) fn blocked_retained_lane() -> serde_json::Value {
    serde_json::json!({"type": "BLOCKED", "blocker": "CAPABILITY_UNAVAILABLE"})
}

pub(crate) fn building_current_lane() -> serde_json::Value {
    serde_json::json!({"type": "BUILDING", "progress": {}})
}

pub(crate) fn building_retained_lane() -> serde_json::Value {
    serde_json::json!({"type": "BUILDING", "progress": {}, "fallback": {"type": "NONE"}})
}

pub(crate) fn ready_runtime_readiness() -> serde_json::Value {
    serde_json::json!({
        "runtime": available_current_lane(1),
        "model": available_current_lane(1),
        "workspaceFiles": available_current_lane(1),
        "compiler": available_current_lane(1),
        "sourceIndex": available_retained_lane(1),
        "references": available_retained_lane(1),
        "semanticGraph": available_retained_lane(1),
        "mutation": available_current_lane(1)
    })
}

pub(crate) fn align_available_retained_lanes_with_publication(
    status: &mut serde_json::Value,
    publication: &serde_json::Value,
) {
    for (lane, revision) in [
        ("sourceIndex", publication["sourceRevision"].as_u64()),
        ("references", publication["referenceRevision"].as_u64()),
        (
            "semanticGraph",
            publication["graphPublication"]["revision"].as_u64(),
        ),
    ] {
        if status["readiness"][lane]["type"] == "AVAILABLE"
            && let Some(revision) = revision
        {
            status["readiness"][lane]["evidence"]["revision"] = revision.into();
        }
    }
}

pub(crate) fn expose_published_retained_lanes(
    status: &mut serde_json::Value,
    publication: &serde_json::Value,
) {
    status["readiness"]["sourceIndex"] =
        available_retained_lane(publication["sourceRevision"].as_u64().unwrap_or(1));
    status["readiness"]["references"] =
        available_retained_lane(publication["referenceRevision"].as_u64().unwrap_or(1));
    status["readiness"]["semanticGraph"] =
        match publication["graphPublication"]["revision"].as_u64() {
            Some(revision) => available_retained_lane(revision),
            None => blocked_retained_lane(),
        };
}

include!("runtime_backend/published_semantic.rs");
include!("runtime_backend/scripted_entrypoints.rs");
include!("runtime_backend/scripted_server.rs");

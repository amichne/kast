const AGENT_RELATION_TOKEN_VERSION: &str = "krp1";
const AGENT_REFERENCE_RELATION: &str = "references";
const AGENT_REFERENCE_PAYLOAD_TAG: &str = "reference";
const AGENT_TRAVERSAL_PAYLOAD_TAG: &str = "traversal";
const AGENT_IMPACT_TOKEN_VERSION: &str = "kip1";
const AGENT_IMPACT_MAX_OFFSET: usize = 10_000;

struct AgentPreparedReusableSelector {
    selector: Option<Value>,
    selector_handle: Option<AgentSelectorHandle>,
    expected: Option<AgentExpectedRelationshipSelector>,
    workspace_root: String,
}

impl AgentPreparedReusableSelector {
    fn traversal_fingerprint(
        &self,
        relation: &str,
        direction: &str,
        depth: Option<u8>,
        limit: u8,
    ) -> String {
        match (&self.expected, &self.selector_handle) {
            (Some(expected), None) => {
                traversal_query_fingerprint(relation, expected, direction, depth, limit)
            }
            (None, Some(handle)) => selector_handle_traversal_query_fingerprint(
                &self.workspace_root,
                relation,
                handle,
                direction,
                depth,
                limit,
            ),
            _ => unreachable!("reusable selector preparation preserves exclusive choice"),
        }
    }

    fn impact_fingerprint(&self, depth: u8, limit: u8) -> String {
        match (&self.expected, &self.selector_handle) {
            (Some(expected), None) => impact_query_fingerprint(expected, depth, limit),
            (None, Some(handle)) => selector_handle_impact_query_fingerprint(
                &self.workspace_root,
                handle,
                depth,
                limit,
            ),
            _ => unreachable!("reusable selector preparation preserves exclusive choice"),
        }
    }
}

fn prepare_reusable_selector(
    public_method: &str,
    runtime: &AgentRuntimeArgs,
    selector: AgentReusableSymbolSelectorArgs,
) -> std::result::Result<AgentPreparedReusableSelector, Box<AgentEnvelope>> {
    let selector = selector.into_selector().map_err(|message| {
        Box::new(error_envelope(
            public_method.to_string(),
            None,
            agent_error("INVALID_SELECTOR_INPUT", message),
        ))
    })?;
    match selector {
        AgentReusableSymbolSelector::Explicit(selector) => {
            let (declaration_file, expected) =
                normalize_relationship_selector(public_method, runtime, &selector)?;
            let workspace_root = expected.workspace_root.clone();
            Ok(AgentPreparedReusableSelector {
                selector: Some(drop_nulls(json!({
                    "fqName": expected.fq_name,
                    "declarationFile": declaration_file,
                    "declarationStartOffset": expected.declaration_start_offset,
                    "kind": expected.kind,
                    "containingType": expected.containing_type,
                }))),
                selector_handle: None,
                expected: Some(expected),
                workspace_root,
            })
        }
        AgentReusableSymbolSelector::Handle(handle) => {
            let normalizer = AgentFilePathNormalizer::from_runtime(runtime).map_err(|error| {
                Box::new(error_envelope(public_method.to_string(), None, error))
            })?;
            Ok(AgentPreparedReusableSelector {
                selector: None,
                selector_handle: Some(handle),
                expected: None,
                workspace_root: normalizer.canonical_root.to_string_lossy().into_owned(),
            })
        }
    }
}

#[derive(Debug, Deserialize)]
struct AgentRawImpactResolveResult {
    #[serde(default)]
    symbol: Option<AgentRawImpactSubject>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawImpactSubject {
    fq_name: String,
    kind: String,
    location: AgentLocationInput,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentSelectorIdentityResponseInput {
    #[serde(rename = "AVAILABLE")]
    Available {
        identity: AgentRelationIdentityProjection,
    },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorHandleRejected {
        reason: AgentSelectorHandleRejectionReason,
        recovery: AgentSelectorHandleRecovery,
    },
}

struct AgentVerifiedImpactSubject {
    selector: Option<Value>,
    subject: Option<AgentRawImpactSubject>,
    identity: AgentRelationIdentityProjection,
    kind: ImpactSubjectKind,
}

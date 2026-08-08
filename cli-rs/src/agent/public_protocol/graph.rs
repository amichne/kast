use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::num::NonZeroU64;
use std::path::Path;

const GRAPH_NODE_SELECTOR_VERSION: &str = "kgns1";
const GRAPH_NODES_PAGE_TOKEN_VERSION: &str = "kgn3";
const MAX_GRAPH_NODE_SELECTOR_LENGTH: usize = 4_096;
const MAX_GRAPH_NODES_PAGE_TOKEN_LENGTH: usize = 128;

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct GraphNodesPageToken {
    workspace_fingerprint: String,
    generation: u64,
    after_id: NonZeroU64,
}

impl GraphNodesPageToken {
    pub(crate) fn issue(
        workspace_fingerprint: String,
        generation: u64,
        after_id: u64,
    ) -> Option<Self> {
        let after_id = NonZeroU64::new(after_id)?;
        Some(Self {
            workspace_fingerprint,
            generation,
            after_id,
        })
    }

    pub(crate) fn parse(value: String) -> Result<Self, GraphNodesPageTokenFailure> {
        if value.len() > MAX_GRAPH_NODES_PAGE_TOKEN_LENGTH
            || !value.is_ascii()
            || value.chars().any(char::is_control)
        {
            return Err(GraphNodesPageTokenFailure::Malformed);
        }
        let fields = value.split('.').collect::<Vec<_>>();
        if fields.len() != 4
            || fields[0] != GRAPH_NODES_PAGE_TOKEN_VERSION
            || !is_lower_hex_24(fields[1])
        {
            return Err(GraphNodesPageTokenFailure::Malformed);
        }
        let generation = canonical_page_u64(fields[2])?;
        let after_id = NonZeroU64::new(canonical_page_u64(fields[3])?)
            .ok_or(GraphNodesPageTokenFailure::Malformed)?;
        Ok(Self {
            workspace_fingerprint: fields[1].to_string(),
            generation,
            after_id,
        })
    }

    pub(crate) fn workspace_fingerprint(&self) -> &str {
        &self.workspace_fingerprint
    }

    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    pub(crate) fn after_id(&self) -> u64 {
        self.after_id.get()
    }

    pub(crate) fn canonical(&self) -> String {
        format!(
            "{GRAPH_NODES_PAGE_TOKEN_VERSION}.{}.{}.{}",
            self.workspace_fingerprint, self.generation, self.after_id
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum GraphNodesPageTokenFailure {
    Malformed,
}

impl GraphNodesPageTokenFailure {
    pub(crate) fn code(self) -> &'static str {
        "GRAPH_PAGE_TOKEN_MALFORMED"
    }

    pub(crate) fn message(self) -> &'static str {
        "The graph page token is malformed or belongs to an obsolete token domain."
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct UntrustedGraphNodeSelector(String);

impl UntrustedGraphNodeSelector {
    pub(crate) fn parse(value: String) -> Result<Self, GraphNodeSelectorFailure> {
        if value.is_empty()
            || value.len() > MAX_GRAPH_NODE_SELECTOR_LENGTH
            || !value.is_ascii()
            || value.chars().any(char::is_control)
        {
            return Err(GraphNodeSelectorFailure::Malformed);
        }
        Ok(Self(value))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct GraphNodeSelector {
    node_id: NonZeroU64,
    stable_key: String,
}

impl GraphNodeSelector {
    pub(crate) fn stable_key(&self) -> &str {
        &self.stable_key
    }

    pub(crate) fn node_id(&self) -> u64 {
        self.node_id.get()
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub(crate) struct IssuedGraphNodeSelector(String);

impl IssuedGraphNodeSelector {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum GraphNodeSelectorFailure {
    Malformed,
    WrongWorkspace,
    Stale,
    Tampered,
}

impl GraphNodeSelectorFailure {
    pub(crate) fn code(self) -> &'static str {
        match self {
            Self::Malformed => "GRAPH_NODE_SELECTOR_MALFORMED",
            Self::WrongWorkspace => "GRAPH_NODE_SELECTOR_WRONG_WORKSPACE",
            Self::Stale => "GRAPH_NODE_SELECTOR_STALE",
            Self::Tampered => "GRAPH_NODE_SELECTOR_TAMPERED",
        }
    }

    pub(crate) fn message(self) -> &'static str {
        match self {
            Self::Malformed => {
                "The graph node selector is malformed or belongs to another selector domain."
            }
            Self::WrongWorkspace => {
                "The graph node selector belongs to a different workspace root."
            }
            Self::Stale => "The graph node selector belongs to an earlier graph generation.",
            Self::Tampered => "The graph node selector failed its bound identity check.",
        }
    }
}

pub(crate) fn graph_workspace_fingerprint(workspace_root: &Path) -> String {
    crate::manifest::sha256_bytes(workspace_root.as_os_str().as_encoded_bytes())[..24].to_string()
}

pub(crate) fn issue_graph_node_selector(
    workspace_root: &Path,
    generation: u64,
    node_id: u64,
    stable_key: &str,
) -> Result<IssuedGraphNodeSelector, GraphNodeSelectorFailure> {
    let node_id = NonZeroU64::new(node_id).ok_or(GraphNodeSelectorFailure::Malformed)?;
    validate_stable_key(stable_key)?;
    let root = graph_workspace_fingerprint(workspace_root);
    let identity = graph_node_identity(&root, generation, node_id, stable_key);
    let key = URL_SAFE_NO_PAD.encode(stable_key.as_bytes());
    Ok(IssuedGraphNodeSelector(format!(
        "{GRAPH_NODE_SELECTOR_VERSION}.{root}.{generation}.{node_id}.{key}.{identity}"
    )))
}

pub(crate) fn authenticate_graph_node_selector(
    workspace_root: &Path,
    generation: u64,
    input: UntrustedGraphNodeSelector,
) -> Result<GraphNodeSelector, GraphNodeSelectorFailure> {
    let fields = input.0.split('.').collect::<Vec<_>>();
    if fields.len() != 6 || fields[0] != GRAPH_NODE_SELECTOR_VERSION {
        return Err(GraphNodeSelectorFailure::Malformed);
    }
    let expected_root = graph_workspace_fingerprint(workspace_root);
    if fields[1] != expected_root {
        return Err(GraphNodeSelectorFailure::WrongWorkspace);
    }
    let issued_generation = canonical_u64(fields[2])?;
    if issued_generation != generation {
        return Err(GraphNodeSelectorFailure::Stale);
    }
    let node_id =
        NonZeroU64::new(canonical_u64(fields[3])?).ok_or(GraphNodeSelectorFailure::Malformed)?;
    let stable_key = URL_SAFE_NO_PAD
        .decode(fields[4])
        .ok()
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .ok_or(GraphNodeSelectorFailure::Malformed)?;
    validate_stable_key(&stable_key)?;
    if URL_SAFE_NO_PAD.encode(stable_key.as_bytes()) != fields[4] {
        return Err(GraphNodeSelectorFailure::Malformed);
    }
    let expected_identity = graph_node_identity(fields[1], generation, node_id, &stable_key);
    if fields[5] != expected_identity {
        return Err(GraphNodeSelectorFailure::Tampered);
    }
    Ok(GraphNodeSelector {
        node_id,
        stable_key,
    })
}

fn graph_node_identity(
    root: &str,
    generation: u64,
    node_id: NonZeroU64,
    stable_key: &str,
) -> String {
    let mut digest = Sha256::new();
    digest.update(b"kast.graph-node-selector.v1\n");
    digest.update(root.as_bytes());
    digest.update(b"\n");
    digest.update(generation.to_string().as_bytes());
    digest.update(b"\n");
    digest.update(node_id.to_string().as_bytes());
    digest.update(b"\n");
    digest.update(stable_key.as_bytes());
    hex::encode(digest.finalize())[..24].to_string()
}

fn canonical_page_u64(value: &str) -> Result<u64, GraphNodesPageTokenFailure> {
    let parsed = value
        .parse::<u64>()
        .map_err(|_| GraphNodesPageTokenFailure::Malformed)?;
    (parsed.to_string() == value)
        .then_some(parsed)
        .ok_or(GraphNodesPageTokenFailure::Malformed)
}

fn is_lower_hex_24(value: &str) -> bool {
    value.len() == 24
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn canonical_u64(value: &str) -> Result<u64, GraphNodeSelectorFailure> {
    let parsed = value
        .parse::<u64>()
        .map_err(|_| GraphNodeSelectorFailure::Malformed)?;
    if parsed.to_string() != value {
        return Err(GraphNodeSelectorFailure::Malformed);
    }
    Ok(parsed)
}

fn validate_stable_key(value: &str) -> Result<(), GraphNodeSelectorFailure> {
    if value.is_empty() || value.len() > 2_048 || value.chars().any(char::is_control) {
        return Err(GraphNodeSelectorFailure::Malformed);
    }
    Ok(())
}

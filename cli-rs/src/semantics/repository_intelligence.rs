use crate::SCHEMA_VERSION;
use crate::agent::{
    NativeGraph, NativeGraphEdge, NativeGraphNode, native_graph_to_csr, native_tarjan_scc,
    native_weighted_leiden,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::runtime;
use crate::source_index_db;
use crate::symbol_query::{SymbolDiscoveryDocument, SymbolDiscoveryField, rank_symbol_discovery};
use crate::workspace_inventory;
use crate::workspace_inventory::model::{
    BuildQualifiedGradleProjectIdentity, BuildQualifiedGradleSourceSetIdentity,
    SourceIndexProgressStatus, WorkspaceCoverageDimension, WorkspaceFileIndexState,
    WorkspaceIndexRead, WorkspaceInventoryFile, WorkspaceRoot, WorkspaceSourceSetEvidence,
};
use rusqlite::types::Type;
use rusqlite::{Connection, OpenFlags, TransactionBehavior};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write;
use std::path::{Path, PathBuf};

const DEFAULT_FILE_LIMIT: usize = 100;
const MAX_FILE_LIMIT: usize = 200;
const GRAPH_COVERAGE_CONTINUATION_VERSION: &str = "kgc1";
const GRAPH_COVERAGE_CONTINUATION_SCHEMA_VERSION: u32 = 1;
const GRAPH_COVERAGE_ORDERING: &str = "path ascending";
const REPOSITORY_CONTINUATION_VERSION: &str = "kri2";
const REPOSITORY_CONTINUATION_SCHEMA_VERSION: u32 = 2;
const REPOSITORY_TRAVERSAL_CONTINUATION_VERSION: &str = "krit2";
const REPOSITORY_TRAVERSAL_CONTINUATION_SCHEMA_VERSION: u32 = 2;

include!("repository_intelligence/contract/request.rs");
include!("repository_intelligence/contract/result.rs");
include!("repository_intelligence/coverage/model.rs");
include!("repository_intelligence/query/entrypoint.rs");
include!("repository_intelligence/coverage/query.rs");
include!("repository_intelligence/query/continuation.rs");
include!("repository_intelligence/query/execution.rs");
include!("repository_intelligence/render/markdown.rs");
include!("repository_intelligence/coverage/read.rs");
include!("repository_intelligence/coverage/scope.rs");
include!("repository_intelligence/discovery/resolve.rs");
include!("repository_intelligence/context/query.rs");
include!("repository_intelligence/context/targets.rs");
include!("repository_intelligence/context/paths.rs");
include!("repository_intelligence/context/relations.rs");
include!("repository_intelligence/context/findings.rs");
include!("repository_intelligence/architecture/query.rs");
include!("repository_intelligence/architecture/projection.rs");
include!("repository_intelligence/architecture/cycles.rs");
include!("repository_intelligence/architecture/findings.rs");
include!("repository_intelligence/architecture/evidence.rs");
include!("repository_intelligence/graph/query.rs");
include!("repository_intelligence/graph/traversal.rs");
include!("repository_intelligence/graph/path.rs");
include!("repository_intelligence/graph/storage.rs");
include!("repository_intelligence/discovery/search.rs");
include!("repository_intelligence/tests.rs");

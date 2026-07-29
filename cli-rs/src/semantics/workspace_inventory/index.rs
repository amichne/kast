use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};

use crate::config;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;

use super::model::{
    BuildQualifiedGradleProjectIdentity, BuildQualifiedGradleSourceSetIdentity,
    KotlinPackageFqName, LegacySourceSetLabel, SourceIndexGeneration, SourceIndexIncompatibility,
    SourceIndexModuleProgress, SourceIndexPendingCount, SourceIndexSnapshotStamp,
    WorkspaceCoverageDimension, WorkspaceEvidenceSource, WorkspaceFileDrift,
    WorkspaceFileIndexState, WorkspaceFilePath, WorkspaceIndexRead, WorkspaceIndexReadFailure,
    WorkspaceIndexSnapshot, WorkspaceInventoryFile, WorkspaceInventoryLimitationCode,
    WorkspaceMatchCoverage, WorkspacePackageEvidence, WorkspacePackageInvalidReference,
    WorkspacePackageUnprovenReason, WorkspaceRoot, WorkspaceSourceSetEvidence,
};

type FileKey = (i64, String);

#[derive(Debug, Clone, Copy)]
enum WorkspaceIndexPathValidation {
    LiveFilesystem,
    PersistedLexical,
}

const ABSOLUTE_PATH_PREFIX: &str = "__kast_abs__/";
const RELATIVE_ESCAPE_PREFIX: &str = "__kast_rel__/";

const REQUIRED_TABLE_COLUMNS: &[(&str, &[&str])] = &[
    ("schema_version", &["version", "generation"]),
    ("path_prefixes", &["prefix_id", "dir_path"]),
    (
        "file_manifest",
        &[
            "prefix_id",
            "filename",
            "last_modified_millis",
            "content_hash",
            "desired_source_version",
            "desired_relationships_version",
            "desired_semantic_graph_version",
            "module_name",
            "source_set",
        ],
    ),
    (
        "file_stage_outcomes",
        &[
            "prefix_id",
            "filename",
            "stage",
            "content_hash",
            "stage_version",
            "stage_input_fingerprint",
            "outcome_status",
            "limitations_json",
        ],
    ),
    (
        "file_metadata",
        &[
            "prefix_id",
            "filename",
            "package_fq_id",
            "package_state",
            "package_unproven_reason",
            "module_path",
            "source_set",
        ],
    ),
    ("fq_names", &["fq_id", "fq_name"]),
    (
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    ),
    (
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    ),
    (
        "module_index_progress",
        &[
            "module_name",
            "relationship_index_status",
            "indexed_file_count",
            "total_file_count",
        ],
    ),
    ("pending_updates", &["applied"]),
];

include!("parts/index/read.rs");
include!("parts/index/progress.rs");
include!("parts/index/manifest.rs");
include!("parts/index/decode.rs");
include!("parts/index/schema/structure.rs");
include!("parts/index/schema/columns.rs");
include!("parts/index/schema/checks.rs");
include!("parts/index/result.rs");
#[cfg(test)]
#[path = "index_regressions.rs"]
mod index_regressions;
include!("parts/index/associations.rs");

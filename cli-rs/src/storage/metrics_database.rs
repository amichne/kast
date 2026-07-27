use crate::config;
use crate::error::{CliError, Result};
use crate::metrics::MetricsRequest;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use glob::Pattern;
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};

include!("metrics_database/model.rs");
include!("metrics_database/database/mod.rs");
include!("metrics_database/helpers.rs");
include!("metrics_database/tests/mod.rs");

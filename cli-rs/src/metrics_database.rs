use crate::config;
use crate::error::{CliError, Result};
use crate::metrics::MetricsRequest;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use glob::Pattern;
use rusqlite::{Connection, ErrorCode, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashSet};
use std::ffi::c_int;
use std::path::{Path, PathBuf};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::Instant;

include!("metrics_database/model.rs");
include!("metrics_database/database.rs");
include!("metrics_database/helpers.rs");
include!("metrics_database/tests.rs");

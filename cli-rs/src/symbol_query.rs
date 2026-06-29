use crate::config;
use crate::error::{CliError, Result};
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use crate::symbol_query_filters::{
    CompiledSymbolQueryFilters, DeclarationFilterInput, SymbolQueryFilterCriteria, UsageFacet,
    is_build_logic_location,
};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

include!("symbol_query/rpc_entrypoint.rs");
include!("symbol_query/model.rs");
include!("symbol_query/database.rs");
include!("symbol_query/ranking_and_filters.rs");
include!("symbol_query/tests.rs");

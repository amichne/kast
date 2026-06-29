use crate::cli::{BackendName, LspArgs, RuntimeArgs};
use crate::config;
use crate::error::{CliError, Result};
use crate::{rpc, runtime};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};

const JSONRPC_VERSION: &str = "2.0";
const MAX_LSP_RESULTS: usize = 100;

include!("lsp/route_model.rs");
include!("lsp/entrypoint_and_client.rs");
include!("lsp/server.rs");
include!("lsp/capabilities_and_routes.rs");
include!("lsp/protocol.rs");
include!("lsp/conversions.rs");
include!("lsp/symbol_mapping.rs");
include!("lsp/tests.rs");

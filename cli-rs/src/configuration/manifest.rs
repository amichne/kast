use crate::bundle::CONTROL_CLI_BUNDLE_PATH;
use crate::error::{CliError, Result};
use crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::fs::OpenOptions;
use std::io::Read;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const INSTALL_MANIFEST_FILE: &str = "receipt.json";
const TOOL_NAME: &str = "kast";
const DEFAULT_PROFILE: &str = "user-local";

include!("manifest/receipt.rs");
include!("manifest/storage.rs");

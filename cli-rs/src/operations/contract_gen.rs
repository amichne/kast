use crate::catalog_schema;
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

const PATH_SAMPLE: &str = "/absolute/path/to/workspace/src/main/kotlin/example/Widget.kt";
const WORKSPACE_SAMPLE: &str = "/absolute/path/to/workspace";

include!("contract_gen/generation.rs");
include!("contract_gen/public.rs");
include!("contract_gen/samples.rs");
include!("contract_gen/tests.rs");

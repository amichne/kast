use crate::error::{CliError, Result};
use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, BTreeSet};

const JSON_SCHEMA_DRAFT: &str = "https://json-schema.org/draft/2020-12/schema";

include!("catalog_schema/request.rs");
include!("catalog_schema/field.rs");
include!("catalog_schema/tests.rs");

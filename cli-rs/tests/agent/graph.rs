#[path = "../support/mod.rs"]
mod support;

use serde_json::{Value, json};
use support::*;

include!("graph/refresh.rs");
include!("graph/readiness.rs");
include!("graph/source_scope.rs");
include!("graph/source_authority.rs");

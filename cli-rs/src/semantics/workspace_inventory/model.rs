use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::path::{Component, Path, PathBuf};

use thiserror::Error;

include!("parts/model/roots.rs");
include!("parts/model/source_index.rs");
include!("parts/model/packages.rs");
include!("parts/model/gradle.rs");
include!("parts/model/file_state.rs");
include!("parts/model/backend.rs");
include!("parts/model/inventory.rs");
include!("parts/model/index_read.rs");

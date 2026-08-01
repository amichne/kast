use crate::SCHEMA_VERSION;
use crate::cli::{BackendName, DaemonStartArgs};
use crate::error::{CliError, Result};
use crate::manifest;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::env;
use std::fmt;
use std::fs;
use std::num::{NonZeroU32, NonZeroU64};
use std::path::{Path, PathBuf};

include!("config/model.rs");
include!("config/partial.rs");
include!("config/load.rs");
include!("config/path_resolution.rs");
include!("config/launch.rs");
include!("config/filesystem.rs");
include!("config/workspace/git.rs");
include!("config/workspace/mutation.rs");

pub(crate) fn validate_toml(contents: &str) -> Result<()> {
    toml::from_str::<PartialConfig>(contents)?;
    Ok(())
}

include!("config/tests/mod.rs");

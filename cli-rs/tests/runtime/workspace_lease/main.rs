use std::process::Command;

#[cfg(target_os = "macos")]
#[path = "../../support/mod.rs"]
mod support;
#[cfg(target_os = "macos")]
use support::*;

include!("lease_lifecycle.rs");
include!("recovery.rs");

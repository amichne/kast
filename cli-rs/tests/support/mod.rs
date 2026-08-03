#![allow(dead_code, unused_imports)]

pub(crate) mod metrics;
pub(crate) mod workspace_files;

pub(crate) use std::path::Path;
pub(crate) use std::path::PathBuf;
pub(crate) use std::process::Command;
pub(crate) use std::{io::BufRead, io::BufReader, io::Write, os::unix::net::UnixListener, thread};

pub(crate) fn api_schema_version() -> u32 {
    env!("KAST_API_SCHEMA_VERSION")
        .parse()
        .expect("authored API schema version")
}

pub(crate) fn prior_api_schema_version() -> u32 {
    api_schema_version()
        .checked_sub(1)
        .expect("API schema version has a prior version")
}

include!("core/install.rs");
include!("core/backends.rs");
include!("core/bundles.rs");

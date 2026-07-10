use crate::SCHEMA_VERSION;
use crate::cli::{BackendName, DemoArgs, DemoView, OutputFormat, PublicDemoArgs, RuntimeArgs};
use crate::config;
use crate::error::{CliError, Result};
use crate::output;
use crate::rpc;
use crate::runtime;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyModifiers};
use crossterm::execute;
use crossterm::terminal::{
    EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode,
};
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap};
use ratatui::{Frame, Terminal};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, IsTerminal, Stdout};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, mpsc};
use std::time::Duration;

const PREVIEW_RADIUS: usize = 7;

include!("demo/model.rs");
include!("demo/entrypoints.rs");
include!("demo/database.rs");
include!("demo/evidence.rs");
include!("demo/story.rs");
include!("demo/public_state.rs");
include!("demo/symbol_app.rs");
include!("demo/compare_app.rs");
include!("demo/tui.rs");
include!("demo/public_tui.rs");
include!("demo/public_rendering.rs");
include!("demo/rendering.rs");
include!("demo/output_and_compare.rs");
include!("demo/tests.rs");

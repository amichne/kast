use rusqlite::{Connection, OptionalExtension, params};
use std::time::Duration;

pub(crate) const BUSY_TIMEOUT_MS: u64 = 5_000;
pub(crate) const MMAP_SIZE_BYTES: i64 = 268_435_456;
pub(crate) const CACHE_SIZE_KIB: i64 = -64_000;

pub(crate) fn configure_read_connection(conn: &Connection) -> rusqlite::Result<()> {
    conn.busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS))?;
    conn.execute_batch(&format!(
        "PRAGMA mmap_size={MMAP_SIZE_BYTES}; \
         PRAGMA cache_size={CACHE_SIZE_KIB}; \
         PRAGMA temp_store=MEMORY;"
    ))?;
    enable_query_only(conn)
}

pub(crate) fn enable_query_only(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch("PRAGMA query_only=ON;")
}

pub(crate) fn trigram_fts_query(query: &str) -> String {
    format!("\"{}\"", query.replace('"', "\"\"").to_lowercase())
}

pub(crate) fn is_short_trigram_query(query: &str) -> bool {
    query.chars().count() < 3
}

pub(crate) fn escape_like(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
}

pub(crate) fn persistent_symbol_fts_exists(conn: &Connection) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        params!["fq_names_fts"],
        |_| Ok(true),
    )
    .optional()
    .map(|value| value.unwrap_or(false))
}

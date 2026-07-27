fn native_graph_measurements(
    connection: &rusqlite::Connection,
    database: &Path,
    load_nanos: u128,
    compute_nanos: u128,
) -> std::result::Result<Value, AgentError> {
    let mut samples = Vec::with_capacity(21);
    for _ in 0..21 {
        let started = std::time::Instant::now();
        connection
            .prepare("SELECT id FROM semantic_symbols WHERE id > ? ORDER BY id LIMIT 100")
            .and_then(|mut statement| {
                statement
                    .query_map([0_i64], |row| row.get::<_, i64>(0))?
                    .collect::<rusqlite::Result<Vec<_>>>()
                    .map(|_| ())
            })
            .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
        samples.push(started.elapsed().as_micros());
    }
    samples.sort_unstable();
    let p95 = samples[(samples.len() * 95).div_ceil(100).saturating_sub(1)];
    Ok(json!({
        "loadNanos": load_nanos,
        "computeNanos": compute_nanos,
        "databaseBytes": std::fs::metadata(database).map(|metadata| metadata.len()).unwrap_or(0),
        "peakRssBytes": native_graph_peak_rss_bytes(),
        "queryP95Micros": p95
    }))
}

#[cfg(unix)]
fn native_graph_peak_rss_bytes() -> u64 {
    let mut usage = std::mem::MaybeUninit::<libc::rusage>::zeroed();
    if unsafe { libc::getrusage(libc::RUSAGE_SELF, usage.as_mut_ptr()) } != 0 {
        return 0;
    }
    let maximum = unsafe { usage.assume_init() }.ru_maxrss.max(0) as u64;
    if cfg!(target_os = "macos") {
        maximum
    } else {
        maximum.saturating_mul(1024)
    }
}

#[cfg(not(unix))]
fn native_graph_peak_rss_bytes() -> u64 {
    0
}

fn native_graph_sql_error(code: &str, error: rusqlite::Error) -> AgentError {
    agent_error(code, format!("Native graph SQLite query failed: {error}"))
}

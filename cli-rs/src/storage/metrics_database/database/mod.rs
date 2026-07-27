pub(crate) struct MetricsDatabase<'a> {
    request: &'a MetricsRequest,
    conn: Connection,
    #[cfg(test)]
    impact_snapshot_barrier: Option<ImpactSnapshotBarrier>,
}

#[cfg(test)]
struct ImpactSnapshotBarrier {
    count_complete: std::sync::Arc<std::sync::Barrier>,
    mutation_complete: std::sync::Arc<std::sync::Barrier>,
}

fn sql_row_bound(value: usize) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

include!("summary.rs");
include!("impact.rs");
include!("graph.rs");
include!("support.rs");

fn reject_effective_repository_base_facts(
    transaction: &rusqlite::Transaction<'_>,
    has_repository_base: bool,
) -> Result<()> {
    if !has_repository_base {
        return Ok(());
    }
    let has_hidden_facts = transaction
        .query_row(
            "SELECT EXISTS (
                 SELECT 1
                 FROM repository_base.semantic_symbols symbols
                 JOIN repository_base.semantic_files files ON files.id = symbols.file_id
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM repository_overlay_tombstones tombstone
                     WHERE tombstone.path = files.path
                 )
                   AND NOT EXISTS (
                     SELECT 1
                     FROM semantic_files overlay
                     WHERE overlay.path = files.path
                       AND overlay.refresh_status != 'CACHED'
                 )
                   AND NOT EXISTS (
                     SELECT 1
                     FROM semantic_symbols overlay
                     WHERE overlay.stable_key = symbols.stable_key
                 )
                 UNION ALL
                 SELECT 1
                 FROM repository_base.semantic_edge_occurrences edges
                 JOIN repository_base.semantic_files source_file
                   ON source_file.id = edges.source_file_id
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM repository_overlay_tombstones tombstone
                     WHERE tombstone.path = source_file.path
                 )
                   AND NOT EXISTS (
                     SELECT 1
                     FROM semantic_files overlay
                     WHERE overlay.path = source_file.path
                       AND overlay.refresh_status != 'CACHED'
                 )
                 LIMIT 1
             )",
            [],
            |row| row.get::<_, bool>(0),
        )
        .map_err(graph_coverage_unavailable)?;
    if has_hidden_facts {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            "repository base contains effective semantic facts that repository execution cannot read",
        ));
    }
    Ok(())
}

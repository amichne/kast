fn derived_topology_data_versions(
    connection: &rusqlite::Connection,
    has_repository_base: bool,
) -> Result<DerivedTopologyDataVersions> {
    let main = connection
        .query_row("PRAGMA main.data_version", [], |row| row.get(0))
        .map_err(derived_topology_query_error)?;
    let repository_base = has_repository_base
        .then(|| {
            connection.query_row("PRAGMA repository_base.data_version", [], |row| row.get(0))
        })
        .transpose()
        .map_err(derived_topology_query_error)?;
    Ok(DerivedTopologyDataVersions {
        main,
        repository_base,
    })
}

fn derived_topology_generation(connection: &rusqlite::Connection) -> Result<u64> {
    let (version, generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(derived_topology_query_error)?;
    if version != crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_SCHEMA_MISMATCH",
            format!(
                "source-index.db uses schema {version}; this Kast build requires {}.",
                crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION
            ),
        ));
    }
    u64::try_from(generation).map_err(|_| {
        CliError::new(
            "DERIVED_TOPOLOGY_SCHEMA_INVALID",
            "The source-index generation is negative.",
        )
    })
}

fn derived_topology_database_error(error: rusqlite::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_INDEX_UNAVAILABLE", error.to_string())
}

fn derived_topology_query_error(error: rusqlite::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_QUERY_FAILED", error.to_string())
}

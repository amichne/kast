fn read_generation(
    transaction: &Transaction<'_>,
) -> Result<SourceIndexGeneration, ReadDatabaseError> {
    let (row_count, version, generation) = transaction
        .query_row(
            "SELECT COUNT(*), MIN(version), MIN(generation) FROM schema_version",
            [],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Option<i64>>(1)?,
                    row.get::<_, Option<i64>>(2)?,
                ))
            },
        )
        .map_err(incompatible_sql)?;
    if row_count != 1 {
        return Err(ReadDatabaseError::Incompatible(format!(
            "schema_version must contain exactly one row, found {row_count}"
        )));
    }
    let version = version.ok_or_else(|| {
        ReadDatabaseError::Incompatible("schema_version.version is unavailable".to_string())
    })?;
    if version != SOURCE_INDEX_SCHEMA_VERSION {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index schema {} is incompatible with required schema {SOURCE_INDEX_SCHEMA_VERSION}",
            version
        )));
    }
    SourceIndexGeneration::try_from_database(generation.ok_or_else(|| {
        ReadDatabaseError::Incompatible("schema_version.generation is unavailable".to_string())
    })?)
    .ok_or_else(|| {
        ReadDatabaseError::Incompatible("source-index generation is negative".to_string())
    })
}

fn read_module_progress(
    transaction: &Transaction<'_>,
) -> Result<(BTreeSet<SourceIndexModuleProgress>, usize), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(
            "SELECT module_name, relationship_index_status, indexed_file_count, total_file_count FROM module_index_progress ORDER BY module_name",
        )
        .map_err(incompatible_sql)?;
    let mut rows = statement.query([]).map_err(incompatible_sql)?;
    let mut progress = BTreeSet::new();
    let mut invalid_count = 0;
    while let Some(row) = rows.next().map_err(incompatible_sql)? {
        let decoded = SourceIndexModuleProgress::from_database(
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
        );
        if let Some(decoded) = decoded {
            progress.insert(decoded);
        } else {
            invalid_count += 1;
        }
    }
    Ok((progress, invalid_count))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SourceFileStageProgress {
    Complete,
    Incomplete,
}

fn read_source_file_stage_progress(
    transaction: &Transaction<'_>,
) -> Result<SourceFileStageProgress, ReadDatabaseError> {
    let (total_count, complete_count) = transaction
        .query_row(
            r#"SELECT COUNT(*),
                      COALESCE(SUM(CASE
                          WHEN outcomes.content_hash = manifest.content_hash
                           AND outcomes.stage_version = manifest.desired_source_version
                           AND outcomes.outcome_status = 'COMPLETE'
                          THEN 1 ELSE 0 END), 0)
               FROM file_manifest manifest
               LEFT JOIN file_stage_outcomes outcomes
                 ON outcomes.prefix_id = manifest.prefix_id
                AND outcomes.filename = manifest.filename
                AND outcomes.stage = 'SOURCE'
               WHERE manifest.filename GLOB '*.kt'"#,
            [],
            |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)),
        )
        .map_err(incompatible_sql)?;
    Ok(
        if total_count > 0 && complete_count == total_count {
            SourceFileStageProgress::Complete
        } else {
            SourceFileStageProgress::Incomplete
        },
    )
}

fn read_pending_count(
    transaction: &Transaction<'_>,
) -> Result<SourceIndexPendingCount, ReadDatabaseError> {
    let invalid_count = transaction
        .query_row(
            "SELECT COUNT(*) FROM pending_updates WHERE applied IS NULL OR typeof(applied) <> 'integer' OR applied NOT IN (0, 1)",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map_err(incompatible_sql)?;
    if invalid_count > 0 {
        return Err(ReadDatabaseError::Incompatible(format!(
            "pending_updates contains {invalid_count} invalid applied states"
        )));
    }
    let count = transaction
        .query_row(
            "SELECT COUNT(*) FROM pending_updates WHERE applied = 0",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map_err(incompatible_sql)?;
    SourceIndexPendingCount::try_from_database(count).ok_or_else(|| {
        ReadDatabaseError::Incompatible("source-index pending count is negative".to_string())
    })
}

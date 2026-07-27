fn read_manifest(transaction: &Transaction<'_>) -> Result<Vec<ManifestRow>, ReadDatabaseError> {
    let mut statement = transaction
        .prepare(
            r#"
            SELECT manifest.prefix_id,
                   manifest.filename,
                   prefixes.dir_path,
                   CASE WHEN metadata.prefix_id IS NULL THEN 0 ELSE 1 END AS metadata_present,
                   metadata.package_state,
                   metadata.package_unproven_reason,
                   metadata.package_fq_id,
                   names.fq_name,
                   metadata.source_set
              FROM file_manifest manifest
              LEFT JOIN path_prefixes prefixes
                ON prefixes.prefix_id = manifest.prefix_id
              LEFT JOIN file_metadata metadata
                ON metadata.prefix_id = manifest.prefix_id
               AND metadata.filename = manifest.filename
              LEFT JOIN fq_names names
                ON names.fq_id = metadata.package_fq_id
             ORDER BY manifest.prefix_id, manifest.filename
            "#,
        )
        .map_err(incompatible_sql)?;
    let rows = statement
        .query_map([], |row| {
            let prefix_id = row.get(0)?;
            let filename: String = row.get(1)?;
            Ok(ManifestRow {
                key: (prefix_id, filename.clone()),
                filename,
                dir_path: row.get(2)?,
                metadata_present: row.get::<_, i64>(3)? != 0,
                package_state: row.get(4)?,
                package_unproven_reason: row.get(5)?,
                package_fq_id: row.get(6)?,
                package_fq_name: row.get(7)?,
                legacy_source_set: row.get(8)?,
            })
        })
        .map_err(incompatible_sql)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)
}

fn read_associations(transaction: &Transaction<'_>) -> Result<AssociationRows, ReadDatabaseError> {
    let mut associations = AssociationRows::default();
    let mut project_statement = transaction
        .prepare(
            "SELECT prefix_id, filename, build_root, project_path FROM file_gradle_projects ORDER BY prefix_id, filename, build_root, project_path",
        )
        .map_err(incompatible_sql)?;
    let mut project_rows = project_statement.query([]).map_err(incompatible_sql)?;
    while let Some(row) = project_rows.next().map_err(incompatible_sql)? {
        let key = (
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
        );
        let identity = BuildQualifiedGradleProjectIdentity::parse(
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
        );
        if let Some(identity) = identity {
            associations
                .projects
                .entry(key)
                .or_default()
                .insert(identity);
        } else {
            *associations.invalid_projects.entry(key).or_default() += 1;
        }
    }

    let mut source_set_statement = transaction
        .prepare(
            "SELECT prefix_id, filename, build_root, project_path, source_set_name FROM file_gradle_source_sets ORDER BY prefix_id, filename, build_root, project_path, source_set_name",
        )
        .map_err(incompatible_sql)?;
    let mut source_set_rows = source_set_statement.query([]).map_err(incompatible_sql)?;
    while let Some(row) = source_set_rows.next().map_err(incompatible_sql)? {
        let key = (
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
        );
        let identity = BuildQualifiedGradleSourceSetIdentity::parse(
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
            row.get(4).map_err(incompatible_sql)?,
        );
        let Some(identity) = identity else {
            *associations.invalid_source_sets.entry(key).or_default() += 1;
            continue;
        };
        let project_exists = associations
            .projects
            .get(&key)
            .is_some_and(|projects| projects.contains(identity.project()));
        if project_exists {
            associations
                .source_sets
                .entry(key)
                .or_default()
                .insert(identity);
        } else {
            *associations.invalid_source_sets.entry(key).or_default() += 1;
        }
    }
    Ok(associations)
}

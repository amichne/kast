impl<'a> MetricsDatabase<'a> {
    fn current_confidence(&self) -> DirectResult<Confidence> {
        let declarations_count = self.count_rows("declarations")?;
        let identifiers_count = self.count_rows("identifier_paths")?;
        let manifest_count = self.count_rows("file_manifest")?;
        let indexed_file_count: i64 = self
            .conn
            .query_row(
                "SELECT COUNT(DISTINCT src_prefix_id || ':' || src_filename) FROM symbol_references",
                [],
                |row| row.get(0),
            )
            .map_err(sql_error)?;
        let index_completeness = if manifest_count == 0 {
            0.0
        } else {
            indexed_file_count.min(manifest_count) as f64 / manifest_count as f64
        };
        let semantic_basis = if declarations_count > 0 {
            "K2_RESOLVED"
        } else if identifiers_count > 0 {
            "LEXICAL"
        } else {
            "HEURISTIC"
        };
        let level = match (semantic_basis, index_completeness) {
            ("K2_RESOLVED", value) if value > 0.95 => "HIGH",
            ("K2_RESOLVED", value) if value > 0.5 => "MEDIUM",
            ("LEXICAL", _) => "LOW",
            _ => "SPECULATIVE",
        };
        Ok(Confidence {
            level: level.to_string(),
            index_completeness,
            semantic_basis: semantic_basis.to_string(),
        })
    }

    fn count_rows(&self, table_name: &str) -> DirectResult<i64> {
        self.conn
            .query_row(&format!("SELECT COUNT(*) FROM {table_name}"), [], |row| {
                row.get(0)
            })
            .map_err(sql_error)
    }

    fn edge_breakdowns_by_target(&self) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                GROUP BY names.fq_name, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        nested_string_map(stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        }))
    }

    fn edge_breakdowns_by_source(&self) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT prefixes.dir_path, refs.src_filename, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                GROUP BY refs.src_prefix_id, refs.src_filename, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?),
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })
            .map_err(sql_error)?;
        let mut values: BTreeMap<String, BTreeMap<String, i64>> = BTreeMap::new();
        for row in rows {
            let (outer, inner, count) = row.map_err(sql_error)?;
            values.entry(outer).or_default().insert(inner, count);
        }
        Ok(values)
    }

    fn edge_breakdowns_by_module_pair(
        &self,
    ) -> DirectResult<BTreeMap<(String, String), BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_meta.module_path, target_meta.module_path, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                WHERE source_meta.module_path IS NOT NULL
                  AND target_meta.module_path IS NOT NULL
                  AND source_meta.module_path <> target_meta.module_path
                GROUP BY source_meta.module_path, target_meta.module_path, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    (row.get::<_, String>(0)?, row.get::<_, String>(1)?),
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })
            .map_err(sql_error)?;
        let mut values: BTreeMap<(String, String), BTreeMap<String, i64>> = BTreeMap::new();
        for row in rows {
            let (outer, inner, count) = row.map_err(sql_error)?;
            values.entry(outer).or_default().insert(inner, count);
        }
        Ok(values)
    }

    fn has_source_symbol_edges(&self) -> DirectResult<bool> {
        self.conn
            .query_row(
                "SELECT 1 FROM symbol_references WHERE source_fq_id IS NOT NULL LIMIT 1",
                [],
                |_| Ok(true),
            )
            .optional()
            .map(|value| value.unwrap_or(false))
            .map_err(sql_error)
    }

    fn symbol_level_impact(
        &self,
        fq_name: &str,
        depth: usize,
        confidence: &Confidence,
        limit: usize,
        offset: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        {
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    WITH RECURSIVE impacted(depth, source_fq_id, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM symbol_references refs
                        WHERE refs.target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                          AND refs.source_fq_id IS NOT NULL
                        UNION ALL
                        SELECT impacted.depth + 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM impacted
                        JOIN symbol_references refs ON refs.target_fq_id = impacted.source_fq_id
                        WHERE impacted.depth < ?
                          AND refs.source_fq_id IS NOT NULL
                    )
                    SELECT source_prefix.dir_path,
                           impacted.src_filename,
                           impacted.depth,
                           via_target_name.fq_name,
                           impacted.edge_kind,
                           COUNT(*) AS reference_count
                    FROM impacted
                    JOIN path_prefixes source_prefix ON source_prefix.prefix_id = impacted.src_prefix_id
                    JOIN fq_names via_target_name ON via_target_name.fq_id = impacted.via_target_fq_id
                    GROUP BY impacted.src_prefix_id, impacted.src_filename, impacted.depth, impacted.via_target_fq_id, impacted.edge_kind
                    ORDER BY impacted.depth ASC,
                             source_prefix.dir_path || '/' || impacted.src_filename ASC,
                             via_target_name.fq_name ASC,
                             impacted.edge_kind ASC
                    LIMIT ? OFFSET ?
                    "#,
                )
                .map_err(sql_error)?;
            self.impact_rows(stmt.query_map(
                params![
                    fq_name,
                    depth as i64,
                    sql_row_bound(limit),
                    sql_row_bound(offset)
                ],
                |row| self.impact_row(row, confidence),
            ))
        }
    }

    fn symbol_level_impact_count(&self, fq_name: &str, depth: usize) -> DirectResult<usize> {
        {
            self.conn
                .query_row(
                    r#"
                    WITH RECURSIVE impacted(depth, source_fq_id, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM symbol_references refs
                        WHERE refs.target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                          AND refs.source_fq_id IS NOT NULL
                        UNION ALL
                        SELECT impacted.depth + 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM impacted
                        JOIN symbol_references refs ON refs.target_fq_id = impacted.source_fq_id
                        WHERE impacted.depth < ?
                          AND refs.source_fq_id IS NOT NULL
                    ),
                    impact_groups AS (
                        SELECT impacted.src_prefix_id,
                               impacted.src_filename,
                               impacted.depth,
                               impacted.via_target_fq_id,
                               impacted.edge_kind
                        FROM impacted
                        GROUP BY impacted.src_prefix_id,
                                 impacted.src_filename,
                                 impacted.depth,
                                 impacted.via_target_fq_id,
                                 impacted.edge_kind
                    )
                    SELECT COUNT(*) FROM impact_groups
                    "#,
                    params![fq_name, depth as i64],
                    |row| row.get::<_, i64>(0),
                )
                .map(|count| usize::try_from(count).expect("non-negative impact count"))
                .map_err(sql_error)
        }
    }

    fn file_level_impact(
        &self,
        fq_name: &str,
        depth: usize,
        confidence: &Confidence,
        limit: usize,
        offset: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        {
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    WITH RECURSIVE impacted_files(depth, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, src_prefix_id, src_filename, target_fq_id, edge_kind
                        FROM symbol_references
                        WHERE target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                        UNION ALL
                        SELECT impacted_files.depth + 1,
                               refs.src_prefix_id,
                               refs.src_filename,
                               refs.target_fq_id,
                               refs.edge_kind
                        FROM impacted_files
                        JOIN symbol_references refs
                          ON refs.tgt_prefix_id = impacted_files.src_prefix_id
                         AND refs.tgt_filename = impacted_files.src_filename
                        WHERE impacted_files.depth < ?
                    ),
                    first_hits AS (
                        SELECT src_prefix_id, src_filename, MIN(depth) AS depth
                        FROM impacted_files
                        GROUP BY src_prefix_id, src_filename
                    )
                    SELECT source_prefix.dir_path,
                           first_hits.src_filename,
                           first_hits.depth,
                           via_target_name.fq_name,
                           impacted_files.edge_kind,
                           COUNT(refs.source_offset) AS reference_count
                    FROM first_hits
                    JOIN impacted_files
                      ON impacted_files.src_prefix_id = first_hits.src_prefix_id
                     AND impacted_files.src_filename = first_hits.src_filename
                     AND impacted_files.depth = first_hits.depth
                    JOIN symbol_references refs
                      ON refs.src_prefix_id = impacted_files.src_prefix_id
                     AND refs.src_filename = impacted_files.src_filename
                     AND refs.target_fq_id = impacted_files.via_target_fq_id
                     AND refs.edge_kind = impacted_files.edge_kind
                    JOIN fq_names via_target_name ON via_target_name.fq_id = impacted_files.via_target_fq_id
                    JOIN path_prefixes source_prefix ON source_prefix.prefix_id = first_hits.src_prefix_id
                    GROUP BY first_hits.src_prefix_id,
                             first_hits.src_filename,
                             first_hits.depth,
                             impacted_files.via_target_fq_id,
                             via_target_name.fq_name,
                             impacted_files.edge_kind
                    ORDER BY first_hits.depth ASC,
                             source_prefix.dir_path || '/' || first_hits.src_filename ASC,
                             via_target_name.fq_name ASC,
                             impacted_files.edge_kind ASC
                    LIMIT ? OFFSET ?
                    "#,
                )
                .map_err(sql_error)?;
            self.impact_rows(stmt.query_map(
                params![
                    fq_name,
                    depth as i64,
                    sql_row_bound(limit),
                    sql_row_bound(offset)
                ],
                |row| self.impact_row(row, confidence),
            ))
        }
    }

    fn file_level_impact_count(&self, fq_name: &str, depth: usize) -> DirectResult<usize> {
        {
            self.conn
                .query_row(
                    r#"
                    WITH RECURSIVE impacted_files(depth, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, src_prefix_id, src_filename, target_fq_id, edge_kind
                        FROM symbol_references
                        WHERE target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                        UNION ALL
                        SELECT impacted_files.depth + 1,
                               refs.src_prefix_id,
                               refs.src_filename,
                               refs.target_fq_id,
                               refs.edge_kind
                        FROM impacted_files
                        JOIN symbol_references refs
                          ON refs.tgt_prefix_id = impacted_files.src_prefix_id
                         AND refs.tgt_filename = impacted_files.src_filename
                        WHERE impacted_files.depth < ?
                    ),
                    first_hits AS (
                        SELECT src_prefix_id, src_filename, MIN(depth) AS depth
                        FROM impacted_files
                        GROUP BY src_prefix_id, src_filename
                    ),
                    impact_groups AS (
                        SELECT first_hits.src_prefix_id,
                               first_hits.src_filename,
                               first_hits.depth,
                               impacted_files.via_target_fq_id,
                               impacted_files.edge_kind
                        FROM first_hits
                        JOIN impacted_files
                          ON impacted_files.src_prefix_id = first_hits.src_prefix_id
                         AND impacted_files.src_filename = first_hits.src_filename
                         AND impacted_files.depth = first_hits.depth
                        GROUP BY first_hits.src_prefix_id,
                                 first_hits.src_filename,
                                 first_hits.depth,
                                 impacted_files.via_target_fq_id,
                                 impacted_files.edge_kind
                    )
                    SELECT COUNT(*) FROM impact_groups
                    "#,
                    params![fq_name, depth as i64],
                    |row| row.get::<_, i64>(0),
                )
                .map(|count| usize::try_from(count).expect("non-negative impact count"))
                .map_err(sql_error)
        }
    }
}

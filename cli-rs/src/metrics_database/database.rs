pub(crate) struct MetricsDatabase<'a> {
    request: &'a MetricsRequest,
    conn: Connection,
    controls: MetricsQueryControls,
    #[cfg(test)]
    impact_snapshot_barrier: Option<ImpactSnapshotBarrier>,
}

#[cfg(test)]
struct ImpactSnapshotBarrier {
    count_complete: Arc<std::sync::Barrier>,
    mutation_complete: Arc<std::sync::Barrier>,
}

fn sql_row_bound(value: usize) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

impl<'a> MetricsDatabase<'a> {
    pub(crate) fn open(request: &'a MetricsRequest) -> DirectResult<Self> {
        Self::open_with_controls(request, MetricsQueryControls::default())
    }

    pub(crate) fn open_with_controls(
        request: &'a MetricsRequest,
        controls: MetricsQueryControls,
    ) -> DirectResult<Self> {
        if !request.database().is_file() {
            return Err(DirectMetricsError::Unavailable(format!(
                "No source-index database exists at {}",
                request.database().display()
            )));
        }
        let conn = Connection::open_with_flags(
            request.database(),
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        let db = Self {
            request,
            conn,
            controls,
            #[cfg(test)]
            impact_snapshot_barrier: None,
        };
        if !db.schema_is_current().map_err(sql_error)? {
            return Err(DirectMetricsError::Unavailable(format!(
                "source-index schema at {} is missing or not version {}",
                request.database().display(),
                SOURCE_INDEX_SCHEMA_VERSION
            )));
        }
        Ok(db)
    }

    pub(crate) fn fan_in(&self, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_target()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       target_meta.module_path,
                       target_meta.source_set,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.src_prefix_id || ':' || refs.src_filename) AS source_file_count,
                       COUNT(DISTINCT source_meta.module_path) AS source_module_count
                FROM symbol_references refs
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path, target_meta.source_set
                ORDER BY occurrence_count DESC,
                         target_name.fq_name ASC,
                         COALESCE(target_prefix.dir_path || '/' || refs.tgt_filename, '') ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let target_fq_name: String = row.get(0)?;
                Ok(FanInMetric {
                    target_path: self.nullable_path(row, 1, 2)?,
                    target_module_path: row.get(3)?,
                    target_source_set: row.get(4)?,
                    occurrence_count: row.get(5)?,
                    source_file_count: row.get(6)?,
                    source_module_count: row.get(7)?,
                    by_edge_kind: edge_breakdowns
                        .get(&target_fq_name)
                        .cloned()
                        .unwrap_or_default(),
                    confidence: confidence.clone(),
                    target_fq_name,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let metric = row.map_err(sql_error)?;
            if self.request.filter().matches(metric.target_path.as_deref()) {
                values.push(metric);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn fan_out(&self, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_source()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_prefix.dir_path,
                       refs.src_filename,
                       source_meta.module_path,
                       source_meta.source_set,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.target_fq_id) AS target_symbol_count,
                       COUNT(DISTINCT CASE
                           WHEN refs.tgt_prefix_id IS NULL THEN NULL
                           ELSE refs.tgt_prefix_id || ':' || refs.tgt_filename
                       END) AS target_file_count,
                       COUNT(DISTINCT target_meta.module_path) AS target_module_count,
                       SUM(CASE WHEN refs.tgt_prefix_id IS NULL OR target_meta.prefix_id IS NULL THEN 1 ELSE 0 END)
                            AS external_target_count
                FROM symbol_references refs
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                GROUP BY refs.src_prefix_id, refs.src_filename, source_meta.module_path, source_meta.source_set
                ORDER BY occurrence_count DESC,
                         source_prefix.dir_path ASC,
                         refs.src_filename ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let source_path =
                    self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?);
                Ok(FanOutMetric {
                    by_edge_kind: edge_breakdowns
                        .get(&source_path)
                        .cloned()
                        .unwrap_or_default(),
                    source_path,
                    source_module_path: row.get(2)?,
                    source_source_set: row.get(3)?,
                    occurrence_count: row.get(4)?,
                    target_symbol_count: row.get(5)?,
                    target_file_count: row.get(6)?,
                    target_module_count: row.get(7)?,
                    external_target_count: row.get(8)?,
                    confidence: confidence.clone(),
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let metric = row.map_err(sql_error)?;
            if self.request.filter().matches(Some(&metric.source_path)) {
                values.push(metric);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn coupling(&self) -> DirectResult<Value> {
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_module_pair()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_meta.module_path, source_meta.source_set,
                       target_meta.module_path, target_meta.source_set,
                       COUNT(*) AS reference_count,
                       SUM(CASE WHEN declarations.visibility = 'PUBLIC' THEN 1 ELSE 0 END) AS public_api_count,
                       SUM(CASE WHEN declarations.visibility = 'INTERNAL' THEN 1 ELSE 0 END) AS internal_leak_count
                FROM symbol_references refs
                JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                LEFT JOIN declarations ON declarations.fq_id = refs.target_fq_id
                WHERE source_meta.module_path IS NOT NULL
                  AND target_meta.module_path IS NOT NULL
                  AND source_meta.module_path <> target_meta.module_path
                GROUP BY source_meta.module_path, source_meta.source_set, target_meta.module_path, target_meta.source_set
                ORDER BY reference_count DESC, source_meta.module_path ASC, target_meta.module_path ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let source_module_path: String = row.get(0)?;
                let target_module_path: String = row.get(2)?;
                Ok(ModuleCouplingMetric {
                    by_edge_kind: edge_breakdowns
                        .get(&(source_module_path.clone(), target_module_path.clone()))
                        .cloned()
                        .unwrap_or_default(),
                    source_module_path,
                    source_source_set: row.get(1)?,
                    target_module_path,
                    target_source_set: row.get(3)?,
                    reference_count: row.get(4)?,
                    public_api_count: row.get(5)?,
                    internal_leak_count: row.get(6)?,
                    confidence: confidence.clone(),
                })
            })
            .map_err(sql_error)?;
        collect_json(rows)
    }

    pub(crate) fn dead_code(&self) -> DirectResult<Value> {
        let confidence = self.current_confidence()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.module_path,
                       declarations.source_set
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM symbol_references refs
                    WHERE refs.target_fq_id = declarations.fq_id
                )
                ORDER BY COALESCE(declarations.module_path, '') ASC,
                         prefixes.dir_path ASC,
                         declarations.filename ASC,
                         names.fq_name ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let visibility: String = row.get(2)?;
                Ok(DeadCodeCandidate {
                    fq_name: row.get(0)?,
                    kind: row.get(1)?,
                    path: self.nullable_path(row, 3, 4)?,
                    module_path: row.get(5)?,
                    source_set: row.get(6)?,
                    confidence: confidence.for_dead_code_visibility(&visibility),
                    reason: dead_code_reason(&visibility).to_string(),
                    visibility,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let candidate = row.map_err(sql_error)?;
            if self.request.filter().matches(candidate.path.as_deref()) {
                values.push(candidate);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn impact(
        &self,
        fq_name: &str,
        depth: usize,
        limit: usize,
    ) -> DirectResult<BoundedMetricsResult> {
        // Impact owns the transaction boundary on this private read-only connection; the
        // shared borrow lets every existing query helper participate in the same snapshot.
        let snapshot = self.conn.unchecked_transaction().map_err(sql_error)?;
        let total_count = self.change_impact_count(fq_name, depth)?;
        #[cfg(test)]
        if let Some(barrier) = &self.impact_snapshot_barrier {
            barrier.count_complete.wait();
            barrier.mutation_complete.wait();
        }
        let probe_limit = limit.saturating_add(1);
        let mut nodes = self.change_impact_nodes(fq_name, depth, probe_limit)?;
        nodes.truncate(limit);
        let returned_count = nodes.len();
        let result = BoundedMetricsResult {
            results: serde_json::to_value(nodes).map_err(json_direct_error)?,
            total_count,
            returned_count,
            truncated: total_count > returned_count,
        };
        snapshot.commit().map_err(sql_error)?;
        Ok(result)
    }

    pub(crate) fn search(&self, query: &str, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let trimmed = query.trim();
        let values = if trimmed.is_empty() {
            self.popular_symbols(limit)?
        } else {
            let mut values = self.exact_symbol_match(trimmed, limit)?;
            let mut seen: HashSet<_> = values.iter().cloned().collect();
            let matches = if source_index_db::is_short_trigram_query(trimmed) {
                self.short_symbol_matches(trimmed, limit)?
            } else {
                self.fts_symbol_matches(trimmed, limit).unwrap_or_default()
            };
            for item in matches {
                if seen.insert(item.clone()) {
                    values.push(item);
                }
                if values.len() == limit {
                    break;
                }
            }
            values
        };
        serde_json::to_value(values).map_err(json_direct_error)
    }

    fn change_impact_nodes(
        &self,
        fq_name: &str,
        depth: usize,
        limit: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        if depth == 0 || limit == 0 {
            return Ok(Vec::new());
        }
        let confidence = self.current_confidence()?;
        let symbol_level = self.has_source_symbol_edges()?;
        if self.request.filter().is_empty() {
            return if symbol_level {
                self.symbol_level_impact(fq_name, depth, &confidence, limit, 0)
            } else {
                self.file_level_impact(fq_name, depth, &confidence, limit, 0)
            };
        }

        let fetch_size = limit.max(128);
        let mut offset = 0;
        let mut values = Vec::with_capacity(limit);
        while values.len() < limit {
            let page = if symbol_level {
                self.symbol_level_impact(fq_name, depth, &confidence, fetch_size, offset)?
            } else {
                self.file_level_impact(fq_name, depth, &confidence, fetch_size, offset)?
            };
            let page_size = page.len();
            values.extend(
                page.into_iter()
                    .filter(|node| self.request.filter().matches(Some(&node.source_path)))
                    .take(limit - values.len()),
            );
            if page_size < fetch_size {
                break;
            }
            offset = offset.saturating_add(fetch_size);
        }
        Ok(values)
    }

    fn change_impact_count(&self, fq_name: &str, depth: usize) -> DirectResult<usize> {
        if depth == 0 {
            return Ok(0);
        }
        if self.request.filter().is_empty() {
            return if self.has_source_symbol_edges()? {
                self.symbol_level_impact_count(fq_name, depth)
            } else {
                self.file_level_impact_count(fq_name, depth)
            };
        }

        let confidence = self.current_confidence()?;
        let symbol_level = self.has_source_symbol_edges()?;
        let mut offset = 0;
        let mut count = 0;
        loop {
            let page = if symbol_level {
                self.symbol_level_impact(fq_name, depth, &confidence, 256, offset)?
            } else {
                self.file_level_impact(fq_name, depth, &confidence, 256, offset)?
            };
            let page_size = page.len();
            count += page
                .iter()
                .filter(|node| self.request.filter().matches(Some(&node.source_path)))
                .count();
            if page_size < 256 {
                break;
            }
            offset = offset.saturating_add(256);
        }
        Ok(count)
    }

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
        self.with_query_progress(|| {
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
                    ORDER BY impacted.depth ASC, reference_count DESC, source_prefix.dir_path ASC, impacted.src_filename ASC, via_target_name.fq_name ASC
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
        })
    }

    fn symbol_level_impact_count(&self, fq_name: &str, depth: usize) -> DirectResult<usize> {
        self.with_query_progress(|| {
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
        })
    }

    fn file_level_impact(
        &self,
        fq_name: &str,
        depth: usize,
        confidence: &Confidence,
        limit: usize,
        offset: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        self.with_query_progress(|| {
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
                             reference_count DESC,
                             source_prefix.dir_path ASC,
                             first_hits.src_filename ASC,
                             via_target_name.fq_name ASC
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
        })
    }

    fn file_level_impact_count(&self, fq_name: &str, depth: usize) -> DirectResult<usize> {
        self.with_query_progress(|| {
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
        })
    }

    fn impact_rows<I>(&self, rows: rusqlite::Result<I>) -> DirectResult<Vec<ChangeImpactNode>>
    where
        I: Iterator<Item = rusqlite::Result<ChangeImpactNode>>,
    {
        let mut values = Vec::new();
        for row in rows.map_err(sql_error)? {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn impact_row(
        &self,
        row: &Row<'_>,
        confidence: &Confidence,
    ) -> rusqlite::Result<ChangeImpactNode> {
        Ok(ChangeImpactNode {
            source_path: self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?),
            depth: row.get::<_, i64>(2)? as usize,
            via_target_fq_name: row.get(3)?,
            edge_kind: row.get(4)?,
            occurrence_count: row.get(5)?,
            confidence: confidence.clone(),
        })
    }

    fn with_query_progress<T>(
        &self,
        operation: impl FnOnce() -> DirectResult<T>,
    ) -> DirectResult<T> {
        if !self.controls.needs_progress_handler() {
            return operation();
        }
        let controls = self.controls.clone();
        let mut remaining_budget = controls.progress_budget;
        self.conn
            .progress_handler(
                controls.progress_ops,
                Some(move || controls.should_cancel(&mut remaining_budget)),
            )
            .map_err(sql_error)?;
        let result = operation();
        let clear_result = self
            .conn
            .progress_handler(0, None::<fn() -> bool>)
            .map_err(sql_error);
        match (result, clear_result) {
            (Ok(value), Ok(())) => Ok(value),
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(error),
        }
    }

    fn popular_symbols(&self, limit: usize) -> DirectResult<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
                GROUP BY names.fq_id
                ORDER BY COUNT(*) DESC, names.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))
    }

    fn exact_symbol_match(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                WHERE names.fq_name = ?
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
    }

    fn short_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        self.with_query_progress(|| {
            let needle = source_index_db::escape_like(&query.to_lowercase());
            let fq_prefix = format!("{needle}%");
            let segment_prefix = format!("%.{}%", needle);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT names.fq_name
                    FROM fq_names names
                    WHERE LOWER(names.fq_name) LIKE ? ESCAPE '\'
                       OR LOWER(names.fq_name) LIKE ? ESCAPE '\'
                    ORDER BY
                        CASE
                            WHEN LOWER(names.fq_name) LIKE ? ESCAPE '\' THEN 0
                            ELSE 1
                        END,
                        LENGTH(names.fq_name),
                        names.fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(
                params![fq_prefix, segment_prefix, fq_prefix, limit as i64],
                |row| row.get(0),
            ))
        })
    }

    fn fts_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        self.with_query_progress(|| {
            let query = source_index_db::trigram_fts_query(query);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT fq_name
                    FROM fq_names_fts
                    WHERE fq_names_fts MATCH ?
                    ORDER BY rank, LENGTH(fq_name), fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
        })
    }

    fn schema_is_current(&self) -> rusqlite::Result<bool> {
        let version = self
            .conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get::<_, i64>(0)
            })
            .optional()?;
        Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION)
            && self.required_tables_exist()?
            && source_index_db::persistent_symbol_fts_exists(&self.conn)?)
    }

    fn required_tables_exist(&self) -> rusqlite::Result<bool> {
        let required = [
            "path_prefixes",
            "fq_names",
            "symbol_references",
            "identifier_paths",
            "file_metadata",
            "file_manifest",
            "declarations",
        ];
        for table in required {
            let exists = self
                .conn
                .query_row(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    params![table],
                    |_| Ok(true),
                )
                .optional()?
                .unwrap_or(false);
            if !exists {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn nullable_path(
        &self,
        row: &Row<'_>,
        dir_column: usize,
        filename_column: usize,
    ) -> rusqlite::Result<Option<String>> {
        let filename: Option<String> = row.get(filename_column)?;
        Ok(filename.map(|filename| {
            let dir = row
                .get::<_, Option<String>>(dir_column)
                .ok()
                .flatten()
                .unwrap_or_default();
            self.compose_path(dir, filename)
        }))
    }

    fn compose_path(&self, relative_dir: String, filename: String) -> String {
        let path = if let Some(absolute) = relative_dir.strip_prefix("__kast_abs__/") {
            PathBuf::from(absolute).join(filename)
        } else {
            let relative = relative_dir
                .strip_prefix("__kast_rel__/")
                .unwrap_or(&relative_dir);
            relative
                .split('/')
                .filter(|segment| !segment.is_empty())
                .fold(
                    self.request.workspace_root().to_path_buf(),
                    |path, segment| path.join(segment),
                )
                .join(filename)
        };
        config::normalize(path).display().to_string()
    }
}

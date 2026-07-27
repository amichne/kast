impl<'a> MetricsDatabase<'a> {
    pub(crate) fn open(request: &'a MetricsRequest) -> DirectResult<Self> {
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
}

impl DemoDatabase {

    fn symbol_hit(&self, fq_name: &str) -> Result<SymbolHit> {
        let detail = self.symbol_detail(fq_name)?;
        Ok(detail
            .map(|detail| SymbolHit {
                fq_name: detail.fq_name,
                simple_name: detail.simple_name,
                kind: detail.kind,
                path: detail.path,
                declaration_offset: detail.declaration_offset,
                module_path: detail.module_path,
                incoming_references: detail.incoming_references,
                outgoing_references: detail.outgoing_references,
            })
            .unwrap_or_else(|| SymbolHit {
                fq_name: fq_name.to_string(),
                simple_name: simple_symbol_name(fq_name).to_string(),
                kind: None,
                path: None,
                declaration_offset: None,
                module_path: None,
                incoming_references: 0,
                outgoing_references: 0,
            }))
    }

    fn symbol_detail(&self, fq_name: &str) -> Result<Option<SymbolDetail>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       declarations.module_path,
                       declarations.source_set
                FROM fq_names names
                LEFT JOIN declarations ON declarations.fq_id = names.fq_id
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE names.fq_name = ?
                ORDER BY
                    CASE declarations.kind
                        WHEN 'CLASS' THEN 0
                        WHEN 'OBJECT' THEN 1
                        WHEN 'INTERFACE' THEN 2
                        WHEN 'FUNCTION' THEN 3
                        WHEN 'PROPERTY' THEN 4
                        ELSE 5
                    END,
                    COALESCE(declarations.filename, '') ASC
                LIMIT 1
                "#,
            )
            .map_err(sql_error)?;
        stmt.query_row(params![fq_name], |row| {
            let fq_name = row.get::<_, String>(0)?;
            Ok(SymbolDetail {
                simple_name: simple_symbol_name(&fq_name).to_string(),
                kind: row.get(1)?,
                visibility: row.get(2)?,
                path: self.nullable_path(row, 3, 4)?,
                declaration_offset: row.get(5)?,
                module_path: row.get(6)?,
                source_set: row.get(7)?,
                incoming_references: self.reference_count_for_target(&fq_name).unwrap_or(0),
                outgoing_references: self.reference_count_for_source(&fq_name).unwrap_or(0),
                by_edge_kind: self.edge_breakdown_for_target(&fq_name).unwrap_or_default(),
                fq_name,
            })
        })
        .optional()
        .map_err(sql_error)
    }

    fn incoming_relations(&self, fq_name: &str, limit: usize) -> Result<Vec<SymbolRelation>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_name.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       MIN(refs.source_offset) AS first_offset,
                       refs.edge_kind,
                       COUNT(*) AS reference_count,
                       source_meta.module_path,
                       source_meta.source_set
                FROM symbol_references refs
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN fq_names source_name ON source_name.fq_id = refs.source_fq_id
                LEFT JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                WHERE target_name.fq_name = ?
                GROUP BY source_name.fq_name,
                         refs.src_prefix_id,
                         refs.src_filename,
                         refs.edge_kind,
                         source_meta.module_path,
                         source_meta.source_set
                ORDER BY reference_count DESC,
                         COALESCE(source_name.fq_name, '') ASC,
                         COALESCE(source_prefix.dir_path, '') ASC,
                         refs.src_filename ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        collect_relations(stmt.query_map(params![fq_name, limit as i64], |row| {
            self.relation_row(row, "incoming")
        }))
    }

    fn outgoing_relations(&self, fq_name: &str, limit: usize) -> Result<Vec<SymbolRelation>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       MIN(refs.target_offset) AS first_offset,
                       refs.edge_kind,
                       COUNT(*) AS reference_count,
                       target_meta.module_path,
                       target_meta.source_set
                FROM symbol_references refs
                JOIN fq_names source_name ON source_name.fq_id = refs.source_fq_id
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                WHERE source_name.fq_name = ?
                GROUP BY target_name.fq_name,
                         refs.tgt_prefix_id,
                         refs.tgt_filename,
                         refs.edge_kind,
                         target_meta.module_path,
                         target_meta.source_set
                ORDER BY reference_count DESC,
                         target_name.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        collect_relations(stmt.query_map(params![fq_name, limit as i64], |row| {
            self.relation_row(row, "outgoing")
        }))
    }

    fn relation_row(
        &self,
        row: &Row<'_>,
        direction: &'static str,
    ) -> rusqlite::Result<SymbolRelation> {
        let fq_name: Option<String> = row.get(0)?;
        let path = self.nullable_path(row, 1, 2)?;
        let fallback_label = path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or("unknown source")
            .to_string();
        let label = fq_name.clone().unwrap_or(fallback_label);
        let simple_name = simple_symbol_name(&label).to_string();
        Ok(SymbolRelation {
            direction,
            fq_name: fq_name.clone(),
            label,
            simple_name,
            path,
            offset: row.get(3)?,
            edge_kind: row.get(4)?,
            references: row.get(5)?,
            module_path: row.get(6)?,
            source_set: row.get(7)?,
            walkable: fq_name.is_some(),
        })
    }

    fn reference_count_for_target(&self, fq_name: &str) -> Result<i64> {
        self.conn
            .query_row(
                r#"
                SELECT COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                WHERE names.fq_name = ?
                "#,
                params![fq_name],
                |row| row.get(0),
            )
            .map_err(sql_error)
    }

    fn reference_count_for_source(&self, fq_name: &str) -> Result<i64> {
        self.conn
            .query_row(
                r#"
                SELECT COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.source_fq_id
                WHERE names.fq_name = ?
                "#,
                params![fq_name],
                |row| row.get(0),
            )
            .map_err(sql_error)
    }

    fn edge_breakdown_for_target(&self, fq_name: &str) -> Result<BTreeMap<String, i64>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                WHERE names.fq_name = ?
                GROUP BY refs.edge_kind
                ORDER BY COUNT(*) DESC, refs.edge_kind ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![fq_name], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .map_err(sql_error)?;
        let mut values = BTreeMap::new();
        for row in rows {
            let (kind, count) = row.map_err(sql_error)?;
            values.insert(kind, count);
        }
        Ok(values)
    }

    fn current_confidence(&self) -> Result<DemoConfidence> {
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
        Ok(DemoConfidence {
            level: level.to_string(),
            index_completeness,
            semantic_basis: semantic_basis.to_string(),
        })
    }

    fn schema_is_current(&self) -> Result<bool> {
        let version = self
            .conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get::<_, i64>(0)
            })
            .optional()
            .map_err(sql_error)?;
        Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION)
            && self.required_tables_exist()?
            && source_index_db::persistent_symbol_fts_exists(&self.conn).map_err(sql_error)?)
    }

    fn required_tables_exist(&self) -> Result<bool> {
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
                .optional()
                .map_err(sql_error)?
                .unwrap_or(false);
            if !exists {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn count_rows(&self, table_name: &str) -> Result<i64> {
        self.conn
            .query_row(&format!("SELECT COUNT(*) FROM {table_name}"), [], |row| {
                row.get(0)
            })
            .map_err(sql_error)
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
                .fold(self.request.workspace_root.clone(), |path, segment| {
                    path.join(segment)
                })
                .join(filename)
        };
        config::normalize(path).display().to_string()
    }
}

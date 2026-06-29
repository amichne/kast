impl DemoDatabase {
    fn open(request: DemoRequest) -> Result<Self> {
        if !request.database.is_file() {
            return Err(CliError::new(
                "DEMO_SOURCE_INDEX_MISSING",
                format!(
                    "No source-index database exists at {}. Run `kast runtime up` for this workspace first.",
                    request.database.display()
                ),
            ));
        }
        let conn = Connection::open_with_flags(
            &request.database,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        let db = Self { request, conn };
        if !db.schema_is_current()? {
            return Err(CliError::new(
                "DEMO_SOURCE_INDEX_STALE",
                format!(
                    "source-index schema at {} is missing or not version {}",
                    db.request.database.display(),
                    SOURCE_INDEX_SCHEMA_VERSION
                ),
            ));
        }
        Ok(db)
    }

    fn snapshot(
        &mut self,
        requested_symbol: Option<&str>,
        query: &str,
        trail: Vec<String>,
    ) -> Result<DemoSnapshot> {
        let search_results = self.search(query, self.request.limit)?;
        let current_name = requested_symbol
            .map(str::to_string)
            .or_else(|| search_results.first().map(|hit| hit.fq_name.clone()));
        let current = current_name
            .as_deref()
            .map(|symbol| self.symbol_detail(symbol))
            .transpose()?
            .flatten();
        let incoming = current
            .as_ref()
            .map(|symbol| self.incoming_relations(&symbol.fq_name, self.request.limit))
            .transpose()?
            .unwrap_or_default();
        let outgoing = current
            .as_ref()
            .map(|symbol| self.outgoing_relations(&symbol.fq_name, self.request.limit))
            .transpose()?
            .unwrap_or_default();
        let preview = current
            .as_ref()
            .map(|symbol| {
                SourcePreview::from_location(
                    symbol.path.as_deref(),
                    symbol.declaration_offset,
                    format!("Declaration: {}", symbol.simple_name),
                )
            })
            .unwrap_or_else(|| SourcePreview::message("No symbol selected"));
        Ok(DemoSnapshot {
            mode: "symbolWalk",
            workspace_root: self.request.workspace_root.display().to_string(),
            database: self.request.database.display().to_string(),
            query: query.to_string(),
            current,
            search_results,
            incoming,
            outgoing,
            preview,
            trail,
            index: self.index()?,
        })
    }

    fn compare_snapshot(&mut self, request: CompareSnapshotRequest<'_>) -> Result<CompareSnapshot> {
        let mut lexical_rows = self.lexical_compare_rows(request.query, self.request.limit)?;
        let mut semantic_rows = self.semantic_compare_rows(request.query, self.request.limit)?;
        let mut semantic_filtered = apply_compare_filters(&semantic_rows, request.filters);
        sort_compare_rows(&mut lexical_rows, request.sort);
        sort_compare_rows(&mut semantic_rows, request.sort);
        sort_compare_rows(&mut semantic_filtered, request.sort);

        let diff_buckets =
            build_compare_diff_buckets(&lexical_rows, &semantic_rows, &semantic_filtered);
        apply_compare_badges(&mut lexical_rows, &semantic_rows, true);
        apply_compare_badges(&mut semantic_filtered, &lexical_rows, false);

        let (left_rows, right_rows) = match request.view_mode {
            CompareViewMode::Full => (lexical_rows.clone(), semantic_filtered.clone()),
            CompareViewMode::Difference => {
                let mut right = diff_buckets.semantic_only.clone();
                right.extend(diff_buckets.filtered_out.clone());
                (diff_buckets.lexical_only.clone(), right)
            }
        };
        let selected_semantic = request
            .selected_semantic
            .min(right_rows.len().saturating_sub(1));
        let selected_lexical = request
            .selected_lexical
            .min(left_rows.len().saturating_sub(1));
        let selected = selected_compare_row(
            request.requested_symbol,
            &left_rows,
            &right_rows,
            selected_lexical,
            selected_semantic,
            request.active_pane,
        );
        let selected_row = selected.map(|(_, _, row)| row);
        let preview = selected_row
            .map(|row| {
                SourcePreview::from_location(
                    row.path.as_deref(),
                    None,
                    format!("Compare: {}", row.label),
                )
            })
            .unwrap_or_else(|| SourcePreview::message("No compare row selected"));
        let selection = CompareSelection {
            pane: selected
                .map(|(pane, _, _)| pane.as_str())
                .unwrap_or_else(|| request.active_pane.as_str()),
            row: selected.map(|(_, index, _)| index).unwrap_or(0),
            fq_name: selected_row.and_then(|row| row.fq_name.clone()),
            label: selected_row.map(|row| row.label.clone()),
        };

        Ok(CompareSnapshot {
            mode: "searchCompare",
            workspace_root: self.request.workspace_root.display().to_string(),
            database: self.request.database.display().to_string(),
            query: request.query.to_string(),
            view_mode: request.view_mode,
            sort: request.sort,
            filters: compare_filter_snapshot(request.filters, &semantic_rows),
            left_pane: ComparePaneSnapshot {
                title: "Lexical index",
                rows: left_rows,
            },
            right_pane: ComparePaneSnapshot {
                title: "Kast semantic",
                rows: right_rows,
            },
            diff_buckets,
            selection,
            preview,
            index: self.index()?,
        })
    }

    fn index(&self) -> Result<DemoIndex> {
        Ok(DemoIndex {
            symbol_count: self.count_rows("fq_names")?,
            file_count: self.count_rows("file_manifest")?,
            reference_count: self.count_rows("symbol_references")?,
            confidence: self.current_confidence()?,
        })
    }

    fn search(&self, query: &str, limit: usize) -> Result<Vec<SymbolHit>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let names = if query.trim().is_empty() {
            self.popular_symbols(limit)?
        } else {
            self.search_symbol_names(query, limit)?
        };
        names
            .into_iter()
            .map(|name| self.symbol_hit(&name))
            .collect()
    }

    fn semantic_compare_rows(&self, query: &str, limit: usize) -> Result<Vec<CompareRow>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let names = if query.trim().is_empty() {
            self.popular_symbols(limit)?
        } else {
            self.search_symbol_names(query, limit)?
        };
        let mut rows = Vec::new();
        for name in names {
            if let Some(detail) = self.symbol_detail(&name)?
                && detail.kind.is_some()
            {
                rows.push(compare_row_from_detail(detail, CompareBadge::Common));
            }
        }
        Ok(rows)
    }

    fn lexical_compare_rows(&self, query: &str, limit: usize) -> Result<Vec<CompareRow>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut rows = Vec::new();
        let mut seen = BTreeSet::new();
        for row in self.semantic_compare_rows(query, limit)? {
            seen.insert(compare_row_key(&row));
            rows.push(row);
        }
        if query.trim().is_empty() || rows.len() >= limit {
            return Ok(rows);
        }

        let needle = source_index_db::escape_like(&query.to_lowercase());
        let pattern = format!("%{needle}%");
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT paths.identifier,
                       prefixes.dir_path,
                       paths.filename,
                       metadata.module_path,
                       metadata.source_set
                FROM identifier_paths paths
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = paths.prefix_id
                LEFT JOIN file_metadata metadata
                  ON metadata.prefix_id = paths.prefix_id
                 AND metadata.filename = paths.filename
                WHERE LOWER(paths.identifier) LIKE ? ESCAPE '\'
                ORDER BY LENGTH(paths.identifier),
                         paths.identifier,
                         COALESCE(prefixes.dir_path, ''),
                         paths.filename
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let candidates = stmt
            .query_map(params![pattern, limit as i64], |row| {
                let dir = row.get::<_, Option<String>>(1)?.unwrap_or_default();
                let filename: String = row.get(2)?;
                Ok((
                    row.get::<_, String>(0)?,
                    self.compose_path(dir, filename),
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, Option<String>>(4)?,
                ))
            })
            .map_err(sql_error)?;
        for candidate in candidates {
            let (identifier, path, module_path, source_set) = candidate.map_err(sql_error)?;
            let row = CompareRow {
                id: format!("lexical:{path}:{identifier}"),
                label: identifier,
                fq_name: None,
                kind: None,
                visibility: None,
                path: Some(path),
                module_path,
                source_set,
                relation_kinds: Vec::new(),
                incoming_references: 0,
                outgoing_references: 0,
                group_path: Vec::new(),
                depth: 0,
                badge: CompareBadge::LexicalOnly,
            };
            if seen.insert(compare_row_key(&row)) {
                rows.push(row);
            }
            if rows.len() == limit {
                break;
            }
        }
        Ok(rows)
    }

    fn search_symbol_names(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let query = query.trim();
        let mut values = self.exact_symbol_match(query, limit)?;
        if values.len() < limit {
            let seen: BTreeSet<_> = values.iter().cloned().collect();
            let matches = if source_index_db::is_short_trigram_query(query) {
                self.short_symbol_matches(query, limit)?
            } else {
                self.fts_symbol_matches(query, limit)?
            };
            for name in matches {
                if !seen.contains(&name) {
                    values.push(name);
                }
                if values.len() == limit {
                    break;
                }
            }
        }
        Ok(values)
    }

    fn popular_symbols(&self, limit: usize) -> Result<Vec<String>> {
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
        let mut values = string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))?;
        if values.is_empty() {
            let mut fallback = self
                .conn
                .prepare("SELECT fq_name FROM fq_names ORDER BY fq_name ASC LIMIT ?")
                .map_err(sql_error)?;
            values = string_column(fallback.query_map(params![limit as i64], |row| row.get(0)))?;
        }
        Ok(values)
    }

    fn exact_symbol_match(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT fq_name
                FROM fq_names
                WHERE fq_name = ?
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
    }

    fn short_symbol_matches(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let needle = source_index_db::escape_like(&query.to_lowercase());
        let fq_prefix = format!("{needle}%");
        let segment_prefix = format!("%.{}%", needle);
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT fq_name
                FROM fq_names
                WHERE LOWER(fq_name) LIKE ? ESCAPE '\'
                   OR LOWER(fq_name) LIKE ? ESCAPE '\'
                ORDER BY
                    CASE
                        WHEN LOWER(fq_name) LIKE ? ESCAPE '\' THEN 0
                        ELSE 1
                    END,
                    LENGTH(fq_name),
                    fq_name
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(
            params![fq_prefix, segment_prefix, fq_prefix, limit as i64],
            |row| row.get(0),
        ))
    }

    fn fts_symbol_matches(&self, query: &str, limit: usize) -> Result<Vec<String>> {
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
    }

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

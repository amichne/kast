impl DemoDatabase {
    fn open(request: DemoRequest) -> Result<Self> {
        if !request.database.is_file() {
            return Err(CliError::new(
                "DEMO_SOURCE_INDEX_MISSING",
                format!(
                    "No source-index database exists at {}. Start the workspace through the Kast IDE plugin or an installed headless distribution first.",
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
                JOIN declarations declarations ON declarations.fq_id = names.fq_id
                LEFT JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
                GROUP BY names.fq_id
                ORDER BY COUNT(refs.target_fq_id) DESC, names.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))
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
}

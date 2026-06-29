impl<'a> SymbolQueryDatabase<'a> {
    fn open(workspace_root: &'a Path, database: &Path) -> Result<Self> {
        let conn = Connection::open_with_flags(
            database,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        if !schema_is_current(&conn)? {
            return Err(CliError::new(
                "INDEX_UNAVAILABLE",
                format!(
                    "source-index schema at {} is missing or not version {}",
                    database.display(),
                    SOURCE_INDEX_SCHEMA_VERSION
                ),
            ));
        }
        let has_supertypes = table_exists(&conn, "declaration_supertypes")?;
        Ok(Self {
            workspace_root,
            conn,
            has_supertypes,
        })
    }

    fn query(&self, request: SymbolQueryRequest) -> Result<SymbolQueryResponse> {
        let compiled_filters = CompiledSymbolQueryFilters::new(request.filters.criteria())?;
        let modes = QueryModes::from_request(&request);
        let declarations = self.declarations()?;
        let by_key: HashMap<_, _> = declarations
            .iter()
            .cloned()
            .map(|declaration| (declaration.key(), declaration))
            .collect();
        let mut candidates = BTreeMap::<DeclarationKey, Candidate>::new();
        let terms = query_terms(&request.query);

        for declaration in declarations {
            if !compiled_filters.matches(declaration.filter_input()) {
                continue;
            }
            let exact_matches = if modes.exact {
                exact_matches(&request.query, &declaration, &request.anchor)
            } else {
                Vec::new()
            };
            let lexical_matches = if modes.lexical {
                self.lexical_matches(&terms, &declaration)?
            } else {
                Vec::new()
            };
            let usage_facets = self.usage_facets(&declaration)?;
            if !compiled_filters.usage_facets_match(&usage_facets) {
                continue;
            }
            let anchored = anchor_matches(&request.anchor, &declaration);
            if !anchored && exact_matches.is_empty() && lexical_matches.is_empty() {
                continue;
            }
            let structural_constraints = structural_constraints(&request.filters);
            let key = declaration.key();
            candidates.insert(
                key,
                Candidate {
                    declaration,
                    usage_facets,
                    exact_matches,
                    lexical_matches,
                    structural_constraints,
                    graph_paths: Vec::new(),
                    discovered_by_graph: false,
                },
            );
        }

        let anchor_fq_id = self.anchor_fq_id(&request.anchor)?;
        if modes.graph {
            for candidate in candidates.values_mut() {
                candidate.graph_paths =
                    self.graph_paths_for(&candidate.declaration, &request.graph, anchor_fq_id)?;
            }
            if let Some(anchor_fq_id) = anchor_fq_id {
                for (key, paths) in self.graph_candidates(anchor_fq_id, &request.graph)? {
                    if let Some(declaration) = by_key.get(&key) {
                        if !compiled_filters.matches(declaration.filter_input()) {
                            continue;
                        }
                        let usage_facets = self.usage_facets(declaration)?;
                        if !compiled_filters.usage_facets_match(&usage_facets) {
                            continue;
                        }
                        candidates
                            .entry(key)
                            .and_modify(|candidate| candidate.graph_paths.extend(paths.clone()))
                            .or_insert_with(|| Candidate {
                                declaration: declaration.clone(),
                                usage_facets,
                                exact_matches: Vec::new(),
                                lexical_matches: Vec::new(),
                                structural_constraints: structural_constraints(&request.filters),
                                graph_paths: paths,
                                discovered_by_graph: true,
                            });
                    }
                }
            }
        }

        let mut ranked: Vec<_> = candidates.into_values().collect();
        ranked.sort_by(compare_candidates);
        ranked.truncate(request.limit);
        let include_next_requests = request.include_next_requests;
        let results = ranked
            .into_iter()
            .enumerate()
            .map(|(index, candidate)| {
                let components = rank_components(&candidate);
                let sort_score = sort_score(&components);
                SymbolQueryResult {
                    declaration: candidate.declaration.result(candidate.usage_facets),
                    rank: Rank {
                        position: index + 1,
                        sort_score,
                        components,
                    },
                    signals: Signals {
                        exact: ExactSignal {
                            matched: !candidate.exact_matches.is_empty(),
                            matches: candidate.exact_matches,
                        },
                        lexical: LexicalSignal {
                            matched: !candidate.lexical_matches.is_empty(),
                            matches: candidate.lexical_matches,
                        },
                        structural: StructuralSignal {
                            matched: true,
                            constraints: candidate.structural_constraints,
                        },
                        graph: GraphSignal {
                            matched: !candidate.graph_paths.is_empty(),
                            paths: candidate.graph_paths,
                        },
                        semantic: SemanticSignal {
                            available: false,
                            matched: false,
                            discovery_only: true,
                            reason: if request.semantic.enabled {
                                "No semantic projection index configured"
                            } else {
                                "Semantic projection index is not configured"
                            },
                        },
                    },
                    next_requests: include_next_requests
                        .then(|| next_requests(&candidate.declaration)),
                }
            })
            .collect();

        Ok(SymbolQueryResponse {
            response_type: "SYMBOL_QUERY_SUCCESS",
            query: request.query,
            available_signals: AvailableSignals {
                exact: true,
                lexical: true,
                structural: true,
                graph: true,
                semantic: false,
            },
            hard_filters: hard_filters(&request.filters),
            results,
        })
    }

    fn declarations(&self) -> Result<Vec<DeclarationRow>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT declarations.fq_id,
                       names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       declarations.prefix_id,
                       COALESCE(prefixes.dir_path, '') AS dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       COALESCE(declarations.module_path, meta.module_path) AS module_path,
                       COALESCE(declarations.source_set, meta.source_set) AS source_set,
                       package_names.fq_name AS package_fq_name
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                LEFT JOIN file_metadata meta
                  ON meta.prefix_id = declarations.prefix_id
                 AND meta.filename = declarations.filename
                LEFT JOIN fq_names package_names ON package_names.fq_id = meta.package_fq_id
                ORDER BY names.fq_name ASC, declarations.prefix_id ASC, declarations.filename ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| self.declaration_row(row))
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn declaration_row(&self, row: &Row<'_>) -> rusqlite::Result<DeclarationRow> {
        let fq_name: String = row.get(1)?;
        let dir_path: String = row.get(5)?;
        let filename: String = row.get(6)?;
        Ok(DeclarationRow {
            fq_id: row.get(0)?,
            simple_name: simple_name(&fq_name).to_string(),
            fq_name,
            kind: row.get(2)?,
            visibility: row.get(3)?,
            prefix_id: row.get(4)?,
            relative_path: relative_path(&dir_path, &filename),
            path: compose_path(self.workspace_root, &dir_path, &filename),
            dir_path,
            filename,
            declaration_offset: row.get(7)?,
            module_path: row.get(8)?,
            source_set: row.get(9)?,
            package_fq_name: row.get(10)?,
        })
    }

    fn lexical_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut matches = Vec::new();
        matches.extend(lexical_field_matches(
            terms,
            "fq_names.fq_name",
            &declaration.fq_name,
        ));
        matches.extend(lexical_field_matches(terms, "file_path", &declaration.path));
        matches.extend(self.identifier_matches(terms, declaration)?);
        matches.extend(self.import_matches(terms, declaration)?);
        Ok(matches)
    }

    fn identifier_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT identifier
                FROM identifier_paths
                WHERE prefix_id = ? AND filename = ?
                ORDER BY identifier ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(
                params![declaration.prefix_id, declaration.filename],
                |row| row.get::<_, String>(0),
            )
            .map_err(sql_error)?;
        let mut identifiers = Vec::new();
        for row in rows {
            identifiers.push(row.map_err(sql_error)?);
        }
        let mut matches = Vec::new();
        for identifier in &identifiers {
            matches.extend(lexical_field_matches(
                terms,
                "identifier_paths.identifier",
                identifier,
            ));
        }
        Ok(matches)
    }

    fn import_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut matches = Vec::new();
        if table_exists(&self.conn, "file_imports")? {
            matches.extend(self.import_table_matches(terms, declaration, "file_imports")?);
        }
        if table_exists(&self.conn, "file_wildcard_imports")? {
            matches.extend(self.import_table_matches(
                terms,
                declaration,
                "file_wildcard_imports",
            )?);
        }
        Ok(matches)
    }

    fn import_table_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
        table_name: &str,
    ) -> Result<Vec<LexicalMatch>> {
        let sql = format!(
            r#"
            SELECT names.fq_name
            FROM {table_name} imports
            JOIN fq_names names ON names.fq_id = imports.fq_id
            WHERE imports.prefix_id = ? AND imports.filename = ?
            ORDER BY names.fq_name ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(
                params![declaration.prefix_id, declaration.filename],
                |row| row.get::<_, String>(0),
            )
            .map_err(sql_error)?;
        let mut imports = Vec::new();
        for row in rows {
            imports.push(row.map_err(sql_error)?);
        }
        let mut matches = Vec::new();
        for import in &imports {
            matches.extend(lexical_field_matches(terms, "import_fq_name", import));
        }
        Ok(matches)
    }

    fn usage_facets(&self, declaration: &DeclarationRow) -> Result<Vec<UsageFacet>> {
        let mut facets = Vec::new();
        match declaration.visibility.as_str() {
            "PUBLIC" => facets.push(UsageFacet::PublicApi),
            "INTERNAL" => facets.push(UsageFacet::InternalApi),
            "PRIVATE" => facets.push(UsageFacet::ModulePrivate),
            _ => {}
        }
        if self.is_bridge_declaration(declaration)? {
            facets.push(UsageFacet::Bridge);
        }
        if is_build_logic_location(declaration.filter_input()) {
            facets.push(UsageFacet::BuildLogic);
        }
        Ok(facets)
    }

    fn is_bridge_declaration(&self, declaration: &DeclarationRow) -> Result<bool> {
        Ok(self.has_direct_incoming_graph_edge(declaration.fq_id)?
            && self.has_direct_outgoing_graph_edge(declaration.fq_id)?)
    }

    fn has_direct_incoming_graph_edge(&self, fq_id: i64) -> Result<bool> {
        let reference = self
            .conn
            .query_row(
                "SELECT 1 FROM symbol_references WHERE target_fq_id = ? LIMIT 1",
                params![fq_id],
                |_| Ok(true),
            )
            .optional()
            .map_err(sql_error)?
            .unwrap_or(false);
        if reference {
            return Ok(true);
        }
        if !self.has_supertypes {
            return Ok(false);
        }
        self.conn
            .query_row(
                "SELECT 1 FROM declaration_supertypes WHERE supertype_fq_id = ? LIMIT 1",
                params![fq_id],
                |_| Ok(true),
            )
            .optional()
            .map(|value| value.unwrap_or(false))
            .map_err(sql_error)
    }

    fn has_direct_outgoing_graph_edge(&self, fq_id: i64) -> Result<bool> {
        let reference = self
            .conn
            .query_row(
                "SELECT 1 FROM symbol_references WHERE source_fq_id = ? LIMIT 1",
                params![fq_id],
                |_| Ok(true),
            )
            .optional()
            .map_err(sql_error)?
            .unwrap_or(false);
        if reference {
            return Ok(true);
        }
        if !self.has_supertypes {
            return Ok(false);
        }
        self.conn
            .query_row(
                "SELECT 1 FROM declaration_supertypes WHERE declaration_fq_id = ? LIMIT 1",
                params![fq_id],
                |_| Ok(true),
            )
            .optional()
            .map(|value| value.unwrap_or(false))
            .map_err(sql_error)
    }

    fn anchor_fq_id(&self, anchor: &SymbolQueryAnchor) -> Result<Option<i64>> {
        if let Some(fq_name) = &anchor.fq_name {
            return self
                .conn
                .query_row(
                    "SELECT fq_id FROM fq_names WHERE fq_name = ?",
                    params![fq_name],
                    |row| row.get(0),
                )
                .optional()
                .map_err(sql_error);
        }
        Ok(None)
    }

    fn graph_paths_for(
        &self,
        declaration: &DeclarationRow,
        graph: &SymbolQueryGraph,
        anchor_fq_id: Option<i64>,
    ) -> Result<Vec<GraphPath>> {
        if let Some(anchor) = anchor_fq_id
            && declaration.fq_id != anchor
        {
            return Ok(Vec::new());
        }
        let mut paths = Vec::new();
        let max_edges = graph.max_edges_per_result as i64;
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            paths.extend(self.symbol_reference_paths(
                "refs.target_fq_id = ?",
                declaration.fq_id,
                graph,
                max_edges,
            )?);
            if self.has_supertypes && graph_includes_inheritance(graph) {
                paths.extend(self.supertype_paths_for(declaration, true, max_edges)?);
            }
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            paths.extend(self.symbol_reference_paths(
                "refs.source_fq_id = ?",
                declaration.fq_id,
                graph,
                max_edges,
            )?);
            if self.has_supertypes && graph_includes_inheritance(graph) {
                paths.extend(self.supertype_paths_for(declaration, false, max_edges)?);
            }
        }
        paths.truncate(graph.max_edges_per_result);
        Ok(paths)
    }

    fn graph_candidates(
        &self,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<BTreeMap<DeclarationKey, Vec<GraphPath>>> {
        let mut values = BTreeMap::new();
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            self.graph_candidate_rows(
                &mut values,
                r#"
                SELECT source_declarations.fq_id,
                       source_declarations.prefix_id,
                       source_declarations.filename,
                       source_names.fq_name,
                       refs.edge_kind,
                       target_names.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       refs.source_offset
                FROM symbol_references refs
                JOIN declarations source_declarations ON source_declarations.fq_id = refs.source_fq_id
                JOIN fq_names source_names ON source_names.fq_id = source_declarations.fq_id
                JOIN fq_names target_names ON target_names.fq_id = refs.target_fq_id
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                WHERE refs.target_fq_id = ?
                "#,
                anchor_fq_id,
                graph,
            )?;
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            self.graph_candidate_rows(
                &mut values,
                r#"
                SELECT target_declarations.fq_id,
                       target_declarations.prefix_id,
                       target_declarations.filename,
                       source_names.fq_name,
                       refs.edge_kind,
                       target_names.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       refs.source_offset
                FROM symbol_references refs
                JOIN declarations target_declarations ON target_declarations.fq_id = refs.target_fq_id
                JOIN fq_names source_names ON source_names.fq_id = refs.source_fq_id
                JOIN fq_names target_names ON target_names.fq_id = target_declarations.fq_id
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                WHERE refs.source_fq_id = ?
                "#,
                anchor_fq_id,
                graph,
            )?;
        }
        if self.has_supertypes && graph_includes_inheritance(graph) {
            self.supertype_candidate_rows(&mut values, anchor_fq_id, graph)?;
        }
        Ok(values)
    }

    fn supertype_candidate_rows(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<()> {
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            self.supertype_candidate_rows_for_direction(
                values,
                r#"
                SELECT declarations.fq_id,
                       declarations.prefix_id,
                       declarations.filename,
                       declaration_names.fq_name,
                       supertype_names.fq_name,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE supertypes.supertype_fq_id = ?
                ORDER BY declaration_names.fq_name ASC
                LIMIT ?
                "#,
                anchor_fq_id,
                graph.max_edges_per_result as i64,
            )?;
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            self.supertype_candidate_rows_for_direction(
                values,
                r#"
                SELECT declarations.fq_id,
                       declarations.prefix_id,
                       declarations.filename,
                       declaration_names.fq_name,
                       supertype_names.fq_name,
                       anchor_prefixes.dir_path,
                       anchor_declarations.filename,
                       anchor_declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.supertype_fq_id
                JOIN declarations anchor_declarations ON anchor_declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes anchor_prefixes ON anchor_prefixes.prefix_id = anchor_declarations.prefix_id
                WHERE supertypes.declaration_fq_id = ?
                ORDER BY supertype_names.fq_name ASC
                LIMIT ?
                "#,
                anchor_fq_id,
                graph.max_edges_per_result as i64,
            )?;
        }
        Ok(())
    }

    fn supertype_candidate_rows_for_direction(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        sql: &str,
        anchor_fq_id: i64,
        limit: i64,
    ) -> Result<()> {
        let mut stmt = self.conn.prepare(sql).map_err(sql_error)?;
        let mut rows = stmt
            .query(params![anchor_fq_id, limit])
            .map_err(sql_error)?;
        while let Some(row) = rows.next().map_err(sql_error)? {
            let key = DeclarationKey {
                fq_id: row.get(0).map_err(sql_error)?,
                prefix_id: row.get(1).map_err(sql_error)?,
                filename: row.get(2).map_err(sql_error)?,
            };
            values.entry(key).or_default().push(GraphPath {
                from_fq_name: row.get(3).map_err(sql_error)?,
                edge_kind: "INHERITANCE".to_string(),
                to_fq_name: row.get(4).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(5).map_err(sql_error)?,
                    &row.get::<_, String>(6).map_err(sql_error)?,
                )),
                source_offset: row.get(7).map_err(sql_error)?,
            });
        }
        Ok(())
    }

    fn graph_candidate_rows(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        base_sql: &str,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<()> {
        let sql = format!(
            "{base_sql} {} ORDER BY refs.source_offset ASC LIMIT ?",
            edge_filter_sql(graph)
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let mut rows = if graph.edge_kinds.is_empty() {
            stmt.query(params![anchor_fq_id, graph.max_edges_per_result as i64])
                .map_err(sql_error)?
        } else {
            let edge_kinds = graph.edge_kinds.join(",");
            stmt.query(params![
                anchor_fq_id,
                edge_kinds,
                graph.max_edges_per_result as i64
            ])
            .map_err(sql_error)?
        };
        while let Some(row) = rows.next().map_err(sql_error)? {
            let key = DeclarationKey {
                fq_id: row.get(0).map_err(sql_error)?,
                prefix_id: row.get(1).map_err(sql_error)?,
                filename: row.get(2).map_err(sql_error)?,
            };
            values.entry(key).or_default().push(GraphPath {
                from_fq_name: row.get(3).map_err(sql_error)?,
                edge_kind: row.get(4).map_err(sql_error)?,
                to_fq_name: row.get(5).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(6).map_err(sql_error)?,
                    &row.get::<_, String>(7).map_err(sql_error)?,
                )),
                source_offset: row.get(8).map_err(sql_error)?,
            });
        }
        Ok(())
    }

    fn symbol_reference_paths(
        &self,
        predicate: &str,
        fq_id: i64,
        graph: &SymbolQueryGraph,
        limit: i64,
    ) -> Result<Vec<GraphPath>> {
        let sql = format!(
            r#"
            SELECT source_names.fq_name,
                   refs.edge_kind,
                   target_names.fq_name,
                   source_prefix.dir_path,
                   refs.src_filename,
                   refs.source_offset
            FROM symbol_references refs
            LEFT JOIN fq_names source_names ON source_names.fq_id = refs.source_fq_id
            JOIN fq_names target_names ON target_names.fq_id = refs.target_fq_id
            JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
            WHERE {predicate}
              {}
            ORDER BY refs.source_offset ASC
            LIMIT ?
            "#,
            edge_filter_sql(graph)
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let mut rows = if graph.edge_kinds.is_empty() {
            stmt.query(params![fq_id, limit]).map_err(sql_error)?
        } else {
            let edge_kinds = graph.edge_kinds.join(",");
            stmt.query(params![fq_id, edge_kinds, limit])
                .map_err(sql_error)?
        };
        self.graph_path_rows(&mut rows)
    }

    fn graph_path_rows(&self, rows: &mut rusqlite::Rows<'_>) -> Result<Vec<GraphPath>> {
        let mut paths = Vec::new();
        while let Some(row) = rows.next().map_err(sql_error)? {
            paths.push(GraphPath {
                from_fq_name: row
                    .get::<_, Option<String>>(0)
                    .map_err(sql_error)?
                    .unwrap_or_else(|| "<unknown>".to_string()),
                edge_kind: row.get(1).map_err(sql_error)?,
                to_fq_name: row.get(2).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(3).map_err(sql_error)?,
                    &row.get::<_, String>(4).map_err(sql_error)?,
                )),
                source_offset: row.get(5).map_err(sql_error)?,
            });
        }
        Ok(paths)
    }

    fn supertype_paths_for(
        &self,
        declaration: &DeclarationRow,
        incoming: bool,
        limit: i64,
    ) -> Result<Vec<GraphPath>> {
        let (predicate, from_column, to_column) = if incoming {
            (
                "supertypes.supertype_fq_id = ?",
                "declaration_names.fq_name",
                "supertype_names.fq_name",
            )
        } else {
            (
                "supertypes.declaration_fq_id = ?",
                "declaration_names.fq_name",
                "supertype_names.fq_name",
            )
        };
        let mut stmt = self
            .conn
            .prepare(&format!(
                r#"
                SELECT {from_column},
                       {to_column},
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE {predicate}
                LIMIT ?
                "#
            ))
            .map_err(sql_error)?;
        let mut rows = stmt
            .query(params![declaration.fq_id, limit])
            .map_err(sql_error)?;
        let mut paths = Vec::new();
        while let Some(row) = rows.next().map_err(sql_error)? {
            paths.push(GraphPath {
                from_fq_name: row.get(0).map_err(sql_error)?,
                edge_kind: "INHERITANCE".to_string(),
                to_fq_name: row.get(1).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(2).map_err(sql_error)?,
                    &row.get::<_, String>(3).map_err(sql_error)?,
                )),
                source_offset: row.get(4).map_err(sql_error)?,
            });
        }
        Ok(paths)
    }
}

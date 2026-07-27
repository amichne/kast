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
}

fn next_requests(declaration: &DeclarationRow) -> NextRequests {
    let kind = declaration.kind.to_ascii_lowercase();
    let symbol_request = json!({
        "symbol": declaration.simple_name,
        "fileHint": declaration.filename,
        "kind": kind
    });
    NextRequests {
        symbol_resolve: NextRequest {
            method: "symbol/resolve",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "includeDeclarationScope": true
            }),
        },
        symbol_references: NextRequest {
            method: "symbol/references",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "includeDeclaration": true
            }),
        },
        symbol_callers: NextRequest {
            method: "symbol/callers",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "direction": "incoming",
                "depth": 1
            }),
        },
        raw_resolve: NextRequest {
            method: "raw/resolve",
            request: json!({
                "position": {
                    "filePath": declaration.path,
                    "offset": declaration.declaration_offset
                },
                "symbol": symbol_request
            }),
        },
    }
}

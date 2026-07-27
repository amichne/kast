impl<'a> SymbolQueryDatabase<'a> {
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
}

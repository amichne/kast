impl<'a> SymbolQueryDatabase<'a> {
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

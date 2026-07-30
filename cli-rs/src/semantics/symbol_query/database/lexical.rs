impl<'a> SymbolQueryDatabase<'a> {
    fn lexical_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
        matches_by_file: &HashMap<i64, HashMap<String, Vec<LexicalMatch>>>,
    ) -> Vec<LexicalMatch> {
        let mut matches = Vec::new();
        matches.extend(lexical_field_matches(
            terms,
            "fq_names.fq_name",
            &declaration.fq_name,
        ));
        matches.extend(lexical_field_matches(terms, "file_path", &declaration.path));
        if let Some(file_matches) = matches_by_file
            .get(&declaration.prefix_id)
            .and_then(|files| files.get(&declaration.filename))
        {
            matches.extend(file_matches.iter().cloned());
        }
        matches
    }

    fn lexical_matches_by_file(
        &self,
        terms: &[String],
    ) -> Result<HashMap<i64, HashMap<String, Vec<LexicalMatch>>>> {
        let mut matches_by_file = HashMap::new();
        if terms.is_empty() {
            return Ok(matches_by_file);
        }

        self.identifier_matches(terms, &mut matches_by_file)?;
        if table_exists(&self.conn, "file_imports")? {
            self.import_table_matches(terms, "file_imports", &mut matches_by_file)?;
        }
        if table_exists(&self.conn, "file_wildcard_imports")? {
            self.import_table_matches(terms, "file_wildcard_imports", &mut matches_by_file)?;
        }
        Ok(matches_by_file)
    }

    fn identifier_matches(
        &self,
        terms: &[String],
        matches_by_file: &mut HashMap<i64, HashMap<String, Vec<LexicalMatch>>>,
    ) -> Result<()> {
        let predicate = matching_terms_sql("identifier", terms.len());
        let sql = format!(
            r#"
            SELECT prefix_id, filename, identifier
            FROM identifier_paths
            WHERE {predicate}
            ORDER BY prefix_id ASC, filename ASC, identifier ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(params_from_iter(terms.iter()), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, identifier) = row.map_err(sql_error)?;
            let matches =
                lexical_field_matches(terms, "identifier_paths.identifier", &identifier);
            extend_file_matches(matches_by_file, prefix_id, filename, matches);
        }
        Ok(())
    }

    fn import_table_matches(
        &self,
        terms: &[String],
        table_name: &str,
        matches_by_file: &mut HashMap<i64, HashMap<String, Vec<LexicalMatch>>>,
    ) -> Result<()> {
        let predicate = matching_terms_sql("names.fq_name", terms.len());
        let sql = format!(
            r#"
            SELECT imports.prefix_id, imports.filename, names.fq_name
            FROM {table_name} imports
            JOIN fq_names names ON names.fq_id = imports.fq_id
            WHERE {predicate}
            ORDER BY imports.prefix_id ASC, imports.filename ASC, names.fq_name ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(params_from_iter(terms.iter()), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, import) = row.map_err(sql_error)?;
            let matches = lexical_field_matches(terms, "import_fq_name", &import);
            extend_file_matches(matches_by_file, prefix_id, filename, matches);
        }
        Ok(())
    }
}

fn matching_terms_sql(field: &str, term_count: usize) -> String {
    (0..term_count)
        .map(|_| format!("instr(lower({field}), ?) > 0"))
        .collect::<Vec<_>>()
        .join(" OR ")
}

fn extend_file_matches(
    matches_by_file: &mut HashMap<i64, HashMap<String, Vec<LexicalMatch>>>,
    prefix_id: i64,
    filename: String,
    matches: Vec<LexicalMatch>,
) {
    if matches.is_empty() {
        return;
    }
    matches_by_file
        .entry(prefix_id)
        .or_default()
        .entry(filename)
        .or_default()
        .extend(matches);
}

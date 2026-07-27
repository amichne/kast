impl<'a> MetricsDatabase<'a> {
    pub(crate) fn impact(
        &self,
        fq_name: &str,
        depth: usize,
        limit: usize,
    ) -> DirectResult<BoundedMetricsResult> {
        self.impact_at(
            fq_name,
            depth,
            limit,
            AgentImpactPageOffset::first(),
            None,
        )
    }

    pub(crate) fn impact_page(
        &self,
        subject: &ImpactSubjectIdentity,
        depth: usize,
        limit: usize,
        offset: AgentImpactPageOffset,
    ) -> DirectResult<BoundedMetricsResult> {
        self.impact_at(&subject.fq_name, depth, limit, offset, Some(subject))
    }

    fn impact_at(
        &self,
        fq_name: &str,
        depth: usize,
        limit: usize,
        offset: AgentImpactPageOffset,
        subject: Option<&ImpactSubjectIdentity>,
    ) -> DirectResult<BoundedMetricsResult> {
        // Impact owns the transaction boundary on this private read-only connection; the
        // shared borrow lets every existing query helper participate in the same snapshot.
        let snapshot = self.conn.unchecked_transaction().map_err(sql_error)?;
        if let Some(subject) = subject {
            self.verify_impact_subject_identity(subject)?;
            if subject.kind.is_callable() {
                return Err(DirectMetricsError::Query(CliError::new(
                    "IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE",
                    "The production declaration key cannot isolate same-file callable overloads for source impact.",
                )));
            }
        }
        let total_count = self.change_impact_count(fq_name, depth)?;
        #[cfg(test)]
        if let Some(barrier) = &self.impact_snapshot_barrier {
            barrier.count_complete.wait();
            barrier.mutation_complete.wait();
        }
        let probe_limit = limit.saturating_add(1);
        let mut nodes = self.change_impact_nodes(fq_name, depth, probe_limit, offset.get())?;
        let has_more = nodes.len() > limit;
        nodes.truncate(limit);
        let returned_count = nodes.len();
        let next_offset = if has_more {
            Some(
                AgentImpactPageOffset::try_from(offset.get().saturating_add(returned_count))
                    .map_err(|message| {
                        DirectMetricsError::Query(CliError::new(
                            "IMPACT_PAGE_OFFSET_LIMIT",
                            message,
                        ))
                    })?,
            )
        } else {
            None
        };
        let result = BoundedMetricsResult {
            results: serde_json::to_value(nodes).map_err(json_direct_error)?,
            total_count,
            returned_count,
            truncated: has_more,
            next_offset,
        };
        snapshot.commit().map_err(sql_error)?;
        Ok(result)
    }

    fn verify_impact_subject_identity(
        &self,
        subject: &ImpactSubjectIdentity,
    ) -> DirectResult<()> {
        let mut statement = self
            .conn
            .prepare(
                r#"
                SELECT path_prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       declarations.kind
                FROM declarations
                JOIN fq_names ON fq_names.fq_id = declarations.fq_id
                JOIN path_prefixes ON path_prefixes.prefix_id = declarations.prefix_id
                WHERE fq_names.fq_name = ?1
                ORDER BY path_prefixes.dir_path ASC, declarations.filename ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = statement
            .query_map(params![&subject.fq_name], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<i64>>(2)?,
                    row.get::<_, String>(3)?,
                ))
            })
            .map_err(sql_error)?;
        let expected_file = std::fs::canonicalize(&subject.declaration_file)
            .unwrap_or_else(|_| subject.declaration_file.clone());
        let mut row_count = 0usize;
        let mut exact_count = 0usize;
        for row in rows {
            let (dir_path, filename, declaration_offset, kind) = row.map_err(sql_error)?;
            row_count = row_count.saturating_add(1);
            let prefix = Path::new(&dir_path);
            let candidate = if prefix.is_absolute() {
                prefix.join(filename)
            } else {
                self.request.workspace_root().join(prefix).join(filename)
            };
            let candidate = std::fs::canonicalize(&candidate).unwrap_or(candidate);
            if candidate == expected_file
                && declaration_offset
                    .and_then(|value| u64::try_from(value).ok())
                    == Some(subject.declaration_start_offset)
                && kind.eq_ignore_ascii_case(subject.kind.as_index_kind())
            {
                exact_count = exact_count.saturating_add(1);
            }
        }
        let isolated = exact_count == 1 && (subject.kind.is_callable() || row_count == 1);
        if isolated {
            Ok(())
        } else {
            Err(DirectMetricsError::Query(CliError::new(
                "IMPACT_INDEX_IDENTITY_UNAVAILABLE",
                "The source index cannot prove the selected declaration identity.",
            )))
        }
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
        initial_offset: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        if depth == 0 || limit == 0 {
            return Ok(Vec::new());
        }
        let confidence = self.current_confidence()?;
        let symbol_level = self.has_source_symbol_edges()?;
        if self.request.filter().is_empty() {
            return if symbol_level {
                self.symbol_level_impact(fq_name, depth, &confidence, limit, initial_offset)
            } else {
                self.file_level_impact(fq_name, depth, &confidence, limit, initial_offset)
            };
        }

        let fetch_size = limit.max(128);
        let mut offset = initial_offset;
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
}

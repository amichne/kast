fn push_metric_results(markdown: &mut String, results: &Value) {
    match results {
        Value::Array(items) if items.is_empty() => {
            push_markdown_line(markdown, format_args!("No rows matched."));
        }
        Value::Array(items) => {
            for item in items.iter().take(20) {
                push_markdown_line(markdown, format_args!("- {}", summarize_value(item)));
            }
            if items.len() > 20 {
                push_markdown_line(
                    markdown,
                    format_args!("- ... {} more rows", items.len() - 20),
                );
            }
        }
        Value::Object(object) => {
            if let Some(nodes) = object.get("nodes").and_then(Value::as_array) {
                push_markdown_line(markdown, format_args!("- Nodes: {}", nodes.len()));
            }
            if let Some(edges) = object.get("edges").and_then(Value::as_array) {
                push_markdown_line(markdown, format_args!("- Edges: {}", edges.len()));
            }
            let summary = summarize_value(results);
            if summary != "object" {
                push_markdown_line(markdown, format_args!("- {summary}"));
            }
        }
        Value::Null => push_markdown_line(markdown, format_args!("No results were returned.")),
        other => push_markdown_line(markdown, format_args!("- {}", summarize_value(other))),
    }
}

fn summarize_value(value: &Value) -> String {
    match value {
        Value::Object(object) => {
            let preferred = [
                "targetFqName",
                "sourceFqName",
                "fqName",
                "filePath",
                "path",
                "modulePath",
                "edgeType",
                "occurrenceCount",
                "referenceCount",
                "incomingReferences",
                "outgoingReferences",
                "focalNodeId",
            ];
            let fields: Vec<_> = preferred
                .iter()
                .filter_map(|key| {
                    object
                        .get(*key)
                        .map(|field| format!("{key}={}", summarize_scalar(field)))
                })
                .collect();
            if fields.is_empty() {
                "object".to_string()
            } else {
                fields.join(", ")
            }
        }
        other => summarize_scalar(other),
    }
}

fn summarize_scalar(value: &Value) -> String {
    match value {
        Value::String(value) => format!("`{value}`"),
        Value::Number(value) => value.to_string(),
        Value::Bool(value) => value.to_string(),
        Value::Array(values) => format!("{} item(s)", values.len()),
        Value::Object(_) => "object".to_string(),
        Value::Null => "null".to_string(),
    }
}

fn metric_display_name(metric: &str) -> &'static str {
    match metric {
        "fanIn" => "fan-in",
        "fanOut" => "fan-out",
        "deadCode" => "dead-code",
        "impact" => "impact",
        "coupling" => "coupling",
        "search" => "search",
        _ => "query",
    }
}

impl MetricsRequest {
    fn from_command(command: MetricsCommand) -> Result<Self> {
        match command {
            MetricsCommand::FanIn(args) => Self::from_limit("fanIn", args),
            MetricsCommand::FanOut(args) => Self::from_limit("fanOut", args),
            MetricsCommand::DeadCode(args) => Self::from_filter("deadCode", args, 50, None, 3),
            MetricsCommand::Impact(args) => Self::from_impact(args),
            MetricsCommand::Coupling(scope) => Self::from_scope("coupling", scope, 50, None, 3),
            MetricsCommand::Search(args) => Self::from_search(args),
        }
    }

    fn from_limit(metric: &'static str, args: MetricsLimitArgs) -> Result<Self> {
        Self::from_filter(metric, args.filter, args.limit, None, 3)
    }

    fn from_impact(args: MetricsImpactArgs) -> Result<Self> {
        Self::from_filter("impact", args.filter, 50, Some(args.symbol), args.depth)
    }

    fn from_search(args: MetricsSearchArgs) -> Result<Self> {
        Self::from_scope("search", args.scope, args.limit, Some(args.query), 3)
    }

    fn from_filter(
        metric: &'static str,
        args: MetricsFilterArgs,
        limit: usize,
        symbol: Option<String>,
        depth: usize,
    ) -> Result<Self> {
        let mut request = Self::from_scope(metric, args.scope, limit, symbol, depth)?;
        request.filter = FileFilter::new(args.file_glob, args.folder_filter)?;
        Ok(request)
    }

    fn from_scope(
        metric: &'static str,
        scope: MetricsScopeArgs,
        limit: usize,
        symbol: Option<String>,
        depth: usize,
    ) -> Result<Self> {
        let workspace_root = config::resolve_workspace_root(scope.workspace_root)?;
        let database = scope
            .database
            .map(config::normalize)
            .unwrap_or(config::workspace_database_path(&workspace_root)?);
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit,
            symbol,
            depth,
            impact_subject: None,
            impact_offset: AgentImpactPageOffset::first(),
            filter: FileFilter::new(None, None)?,
        })
    }

    fn from_rpc_params(
        params: MetricsRpcParams,
        workspace_root_arg: Option<PathBuf>,
    ) -> Result<Self> {
        let metric = match params.metric.as_str() {
            "fanIn" => "fanIn",
            "fanOut" => "fanOut",
            "deadCode" => "deadCode",
            "impact" => "impact",
            "coupling" => "coupling",
            "search" => "search",
            other => {
                return Err(CliError::new(
                    "METRICS_UNSUPPORTED",
                    format!("Unsupported Rust metrics command: {other}"),
                ));
            }
        };
        let workspace_root =
            config::resolve_workspace_root(params.workspace_root.or(workspace_root_arg))?;
        let database = config::workspace_database_path(&workspace_root)?;
        let impact_offset = AgentImpactPageOffset::try_from(params.offset.unwrap_or_default())
            .map_err(|message| CliError::new("IMPACT_PAGE_TOKEN_INVALID", message))?;
        if metric != "impact" && (params.subject.is_some() || params.offset.is_some()) {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "subject and offset are valid only for impact metrics",
            ));
        }
        if metric == "impact" && params.offset.is_some() && params.subject.is_none() {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "an impact offset requires an exact impact subject",
            ));
        }
        if let Some(subject) = params.subject.as_ref()
            && (!subject.is_valid() || params.symbol.as_deref() != Some(subject.fq_name()))
        {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "the impact subject must be complete and match the query symbol",
            ));
        }
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit: params.limit.unwrap_or(50),
            symbol: params.symbol,
            depth: params.depth.unwrap_or(3),
            impact_subject: params.subject,
            impact_offset,
            filter: FileFilter::new(params.file_glob, params.folder_filter)?,
        })
    }

    fn query(&self) -> MetricsQuery {
        MetricsQuery {
            workspace_root: self.workspace_root.display().to_string(),
            metric: self.metric.to_string(),
            limit: self.limit,
            symbol: self.symbol.clone(),
            depth: self.depth,
            subject: self.impact_subject.clone(),
            offset: (self.metric == "impact").then_some(self.impact_offset.get()),
            file_glob: self.filter.file_glob().map(str::to_string),
            folder_filter: self.filter.folder_filter().map(str::to_string),
        }
    }

    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn database(&self) -> &Path {
        &self.database
    }

    pub(crate) fn filter(&self) -> &FileFilter {
        &self.filter
    }

    #[cfg(test)]
    pub(crate) fn for_test(
        workspace_root: PathBuf,
        database: PathBuf,
        metric: &'static str,
        symbol: Option<String>,
        limit: usize,
        depth: usize,
    ) -> Result<Self> {
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit,
            symbol,
            depth,
            impact_subject: None,
            impact_offset: AgentImpactPageOffset::first(),
            filter: FileFilter::new(None, None)?,
        })
    }
}

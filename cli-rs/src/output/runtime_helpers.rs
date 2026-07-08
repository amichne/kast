fn print_candidate(
    document: &mut MarkdownDocument,
    title: &str,
    candidate: &RuntimeCandidateStatus,
) {
    mdln!(document, "## {title}");
    mdln!(
        document,
        "- Backend: `{}`",
        candidate.descriptor.backend_name
    );
    mdln!(
        document,
        "- Backend version: `{}`",
        candidate.descriptor.backend_version
    );
    mdln!(document, "- PID: {}", candidate.descriptor.pid);
    mdln!(document, "- PID alive: {}", yes_no(candidate.pid_alive));
    mdln!(document, "- Reachable: {}", yes_no(candidate.reachable));
    mdln!(document, "- Ready: {}", yes_no(candidate.ready));
    mdln!(document, "- Socket: `{}`", candidate.descriptor.socket_path);
    if let Some(status) = &candidate.runtime_status {
        mdln!(
            document,
            "- Runtime state: `{}`",
            runtime_state(status.state.clone())
        );
        mdln!(document, "- Active: {}", yes_no(status.active));
        mdln!(document, "- Healthy: {}", yes_no(status.healthy));
        mdln!(document, "- Indexing: {}", yes_no(status.indexing));
        print_source_modules(document, &status.source_module_names);
        if let Some(message) = &status.message {
            mdln!(document, "- Message: {message}");
        }
        print_warnings(document, &status.warnings);
    }
    if let Some(error_message) = &candidate.error_message {
        mdln!(document, "- Error: {error_message}");
    }
}

fn print_source_modules(document: &mut MarkdownDocument, module_names: &[String]) {
    let modules = normalized_modules(module_names);
    if modules.is_empty() {
        return;
    }

    let displayed = modules
        .iter()
        .take(SOURCE_MODULE_DISPLAY_LIMIT)
        .cloned()
        .collect::<Vec<_>>();
    let remaining = modules.len().saturating_sub(displayed.len());

    let mut tree = ModuleTree::default();
    for module in displayed {
        tree.insert(&module);
    }

    mdln!(document);
    mdln!(document, "## Source modules");
    tree.print(document);
    if remaining > 0 {
        mdln!(document, "- ... {remaining} more modules");
    }
}

fn normalized_modules(module_names: &[String]) -> Vec<Vec<String>> {
    module_names
        .iter()
        .filter_map(|module_name| normalize_module_name(module_name))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

fn normalize_module_name(module_name: &str) -> Option<Vec<String>> {
    let trimmed = module_name.trim();
    if trimmed.is_empty() {
        return None;
    }

    let without_root = trimmed.trim_start_matches(':');
    let parts = without_root
        .split(':')
        .filter(|part| !part.is_empty())
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();

    if parts.is_empty() {
        Some(vec![trimmed.to_string()])
    } else {
        Some(parts)
    }
}

#[derive(Default)]
struct ModuleTree {
    children: BTreeMap<String, ModuleTree>,
}

impl ModuleTree {
    fn insert(&mut self, path: &[String]) {
        let Some((first, rest)) = path.split_first() else {
            return;
        };
        self.children.entry(first.clone()).or_default().insert(rest);
    }

    fn print(&self, document: &mut MarkdownDocument) {
        self.print_at_depth(document, 0);
    }

    fn print_at_depth(&self, document: &mut MarkdownDocument, depth: usize) {
        let indent = "  ".repeat(depth);
        for (name, child) in &self.children {
            mdln!(document, "{indent}- `{name}`");
            child.print_at_depth(document, depth + 1);
        }
    }
}

fn runtime_state(state: RuntimeState) -> &'static str {
    match state {
        RuntimeState::Starting => "STARTING",
        RuntimeState::Indexing => "INDEXING",
        RuntimeState::Ready => "READY",
        RuntimeState::Degraded => "DEGRADED",
    }
}

fn print_warnings(document: &mut MarkdownDocument, warnings: &[String]) {
    print_messages(document, "Warnings", warnings);
}

fn print_messages(document: &mut MarkdownDocument, title: &str, messages: &[String]) {
    if messages.is_empty() {
        return;
    }
    mdln!(document);
    mdln!(document, "## {title}");
    for message in messages {
        mdln!(document, "- {message}");
    }
}

fn yes_no(value: bool) -> &'static str {
    if value { "yes" } else { "no" }
}

fn value_or_dash(value: &str) -> &str {
    if value.trim().is_empty() { "-" } else { value }
}

fn native_graph_text_edges(
    connection: &rusqlite::Connection,
    sql: &str,
    positions: &BTreeMap<String, usize>,
) -> std::result::Result<Vec<NativeGraphEdge>, AgentError> {
    let mut statement = connection
        .prepare(sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, f64>(4)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    Ok(rows
        .into_iter()
        .filter_map(|(source, target, kind, context, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                kind,
                context,
                weight,
            })
        })
        .collect())
}

pub(crate) fn native_graph_to_csr(
    nodes: Vec<NativeGraphNode>,
    edges: Vec<NativeGraphEdge>,
) -> NativeGraph {
    let mut rows = vec![BTreeSet::<usize>::new(); nodes.len()];
    for edge in &edges {
        rows[edge.source].insert(edge.target);
    }
    let mut offsets = Vec::with_capacity(nodes.len() + 1);
    let mut targets = Vec::new();
    offsets.push(0);
    for row in rows {
        for target in row {
            targets.push(target);
        }
        offsets.push(targets.len());
    }
    NativeGraph {
        nodes,
        edges,
        offsets,
        targets,
    }
}

fn native_connected_components(graph: &NativeGraph) -> Vec<usize> {
    let mut undirected = vec![Vec::new(); graph.nodes.len()];
    for edge in &graph.edges {
        undirected[edge.source].push(edge.target);
        undirected[edge.target].push(edge.source);
    }
    for row in &mut undirected {
        row.sort_unstable();
        row.dedup();
    }
    let mut component = vec![usize::MAX; graph.nodes.len()];
    let mut next_component = 0;
    for root in 0..graph.nodes.len() {
        if component[root] != usize::MAX {
            continue;
        }
        component[root] = next_component;
        let mut queue = std::collections::VecDeque::from([root]);
        while let Some(node) = queue.pop_front() {
            for &target in &undirected[node] {
                if component[target] == usize::MAX {
                    component[target] = next_component;
                    queue.push_back(target);
                }
            }
        }
        next_component += 1;
    }
    component
}

pub(crate) fn native_tarjan_scc(graph: &NativeGraph) -> Vec<usize> {
    #[derive(Clone, Copy)]
    struct VisitFrame {
        node: usize,
        next_edge: usize,
    }

    let count = graph.nodes.len();
    let mut next_index = 0;
    let mut indices = vec![usize::MAX; count];
    let mut lowlink = vec![0; count];
    let mut stack = Vec::new();
    let mut on_stack = vec![false; count];
    let mut components = Vec::new();

    for root in 0..count {
        if indices[root] != usize::MAX {
            continue;
        }
        indices[root] = next_index;
        lowlink[root] = next_index;
        next_index += 1;
        stack.push(root);
        on_stack[root] = true;
        let mut visits = vec![VisitFrame {
            node: root,
            next_edge: graph.offsets[root],
        }];

        while let Some(frame) = visits.last_mut() {
            let node = frame.node;
            if frame.next_edge < graph.offsets[node + 1] {
                let target = graph.targets[frame.next_edge];
                frame.next_edge += 1;
                if indices[target] == usize::MAX {
                    indices[target] = next_index;
                    lowlink[target] = next_index;
                    next_index += 1;
                    stack.push(target);
                    on_stack[target] = true;
                    visits.push(VisitFrame {
                        node: target,
                        next_edge: graph.offsets[target],
                    });
                } else if on_stack[target] {
                    lowlink[node] = lowlink[node].min(indices[target]);
                }
                continue;
            }

            visits.pop();
            if lowlink[node] == indices[node] {
                let mut component = Vec::new();
                while let Some(member) = stack.pop() {
                    on_stack[member] = false;
                    component.push(member);
                    if member == node {
                        break;
                    }
                }
                component.sort_unstable();
                components.push(component);
            }
            if let Some(parent) = visits.last() {
                lowlink[parent.node] = lowlink[parent.node].min(lowlink[node]);
            }
        }
    }
    components.sort_by_key(|component| component[0]);
    let mut membership = vec![0; count];
    for (component_id, component) in components.iter().enumerate() {
        for &node in component {
            membership[node] = component_id;
        }
    }
    membership
}

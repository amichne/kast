fn derive_reference_topology(
    snapshot: ReferenceTopologySnapshot,
    previous: Option<&DerivedTopologyArtifact>,
) -> DerivedTopologyArtifact {
    let positions = snapshot
        .nodes
        .iter()
        .enumerate()
        .map(|(index, node)| (node.key.clone(), index))
        .collect::<BTreeMap<_, _>>();
    let graph = native_graph_to_csr(
        snapshot
            .nodes
            .iter()
            .map(|node| NativeGraphNode {
                database_id: None,
                key: node.key.clone(),
            })
            .collect(),
        snapshot
            .edges
            .iter()
            .filter_map(|edge| {
                Some(NativeGraphEdge {
                    source: *positions.get(&edge.source)?,
                    target: *positions.get(&edge.target)?,
                    occurrence_count: edge.occurrence_count,
                    weight: edge.normalized_weight,
                })
            })
            .collect(),
    );
    let memberships = native_weighted_leiden(&graph, DERIVED_TOPOLOGY_RESOLUTION);
    let (nodes, communities) =
        derived_topology_projection(&snapshot.nodes, &snapshot.edges, &positions, &memberships);
    let (lineage, changes) = previous.map_or((None, None), |previous| {
        (
            Some(derived_topology_lineage(previous, &communities)),
            Some(derived_topology_changes(previous, &nodes, &snapshot.edges)),
        )
    });
    DerivedTopologyArtifact {
        r#type: "KAST_DERIVED_TOPOLOGY".to_string(),
        schema_version: DERIVED_TOPOLOGY_SCHEMA_VERSION,
        evidence_class: DerivedEvidenceClass::StatisticalDerivation,
        source: DerivedTopologySource {
            lane: DerivedSourceLane::ReferenceDerived,
            qualification: snapshot.qualification,
            generation: snapshot.generation,
            input_digest: snapshot.input_digest,
            coverage: snapshot.coverage,
        },
        algorithm: DerivedTopologyAlgorithm {
            name: DerivedAlgorithmName::KastDeterministicPartitionV1,
            version: DERIVED_TOPOLOGY_ALGORITHM_VERSION,
            resolution: DERIVED_TOPOLOGY_RESOLUTION,
            weighting: DerivedWeighting::Log1pOccurrenceCount,
        },
        nodes,
        edges: snapshot.edges,
        communities,
        lineage,
        changes,
    }
}

fn derived_topology_projection(
    inputs: &[ReferenceNodeInput],
    edges: &[ReferenceEdgeInput],
    positions: &BTreeMap<String, usize>,
    memberships: &[usize],
) -> (Vec<DerivedTopologyNode>, Vec<DerivedTopologyCommunity>) {
    let mut incoming = vec![0usize; inputs.len()];
    let mut outgoing = vec![0usize; inputs.len()];
    let mut cross_community = vec![0usize; inputs.len()];
    let mut weighted_degree = vec![0.0; inputs.len()];
    for edge in edges {
        let (Some(&source), Some(&target)) =
            (positions.get(&edge.source), positions.get(&edge.target))
        else {
            continue;
        };
        outgoing[source] += edge.occurrence_count;
        incoming[target] += edge.occurrence_count;
        weighted_degree[source] += edge.normalized_weight;
        weighted_degree[target] += edge.normalized_weight;
        if memberships[source] != memberships[target] {
            cross_community[source] += edge.occurrence_count;
            cross_community[target] += edge.occurrence_count;
        }
    }
    let mut members = BTreeMap::<usize, Vec<usize>>::new();
    for (node, &community) in memberships.iter().enumerate() {
        members.entry(community).or_default().push(node);
    }
    let communities = members
        .iter()
        .map(|(&community, members)| {
            derived_topology_community(
                community,
                members,
                inputs,
                edges,
                positions,
                &weighted_degree,
            )
        })
        .collect::<Vec<_>>();
    let community_terms = communities
        .iter()
        .map(|community| (community.id, community.label_terms.clone()))
        .collect::<BTreeMap<_, _>>();
    let nodes = inputs
        .iter()
        .enumerate()
        .map(|(index, input)| {
            let mut roles = BTreeSet::new();
            if incoming[index] + outgoing[index] == 0 {
                roles.insert(DerivedStructuralRole::Isolated);
            } else {
                if incoming[index] == 0 {
                    roles.insert(DerivedStructuralRole::Source);
                }
                if outgoing[index] == 0 {
                    roles.insert(DerivedStructuralRole::Sink);
                }
                roles.insert(if cross_community[index] > 0 {
                    DerivedStructuralRole::Connector
                } else {
                    DerivedStructuralRole::Internal
                });
            }
            let mut retrieval_terms = semantic_label_terms(&input.name);
            retrieval_terms.extend(semantic_label_terms(&input.key));
            if let Some(module) = &input.module {
                retrieval_terms.extend(semantic_label_terms(module));
            }
            retrieval_terms.extend(
                community_terms
                    .get(&memberships[index])
                    .into_iter()
                    .flatten()
                    .cloned(),
            );
            DerivedTopologyNode {
                input: input.clone(),
                community: memberships[index],
                roles: roles.into_iter().collect(),
                degree: incoming[index] + outgoing[index],
                weighted_degree: weighted_degree[index],
                retrieval_terms: retrieval_terms.into_iter().collect(),
            }
        })
        .collect();
    (nodes, communities)
}

fn derived_topology_community(
    community: usize,
    members: &[usize],
    nodes: &[ReferenceNodeInput],
    edges: &[ReferenceEdgeInput],
    positions: &BTreeMap<String, usize>,
    weighted_degree: &[f64],
) -> DerivedTopologyCommunity {
    let member_set = members.iter().copied().collect::<BTreeSet<_>>();
    let mut internal_edge_count = 0;
    let mut external_edge_count = 0;
    let mut internal_weight = 0.0;
    let mut external_weight = 0.0;
    let mut relationship_kinds = BTreeMap::new();
    for edge in edges {
        let (Some(&source), Some(&target)) =
            (positions.get(&edge.source), positions.get(&edge.target))
        else {
            continue;
        };
        let source_member = member_set.contains(&source);
        let target_member = member_set.contains(&target);
        if source_member && target_member {
            internal_edge_count += 1;
            internal_weight += edge.normalized_weight;
        } else if source_member || target_member {
            external_edge_count += 1;
            external_weight += edge.normalized_weight;
        } else {
            continue;
        }
        *relationship_kinds.entry(edge.kind).or_default() += edge.occurrence_count;
    }
    let mut term_counts = BTreeMap::<String, usize>::new();
    for &member in members {
        for term in semantic_label_terms(&nodes[member].name) {
            *term_counts.entry(term).or_default() += 1;
        }
    }
    let mut ranked_terms = term_counts.into_iter().collect::<Vec<_>>();
    ranked_terms.sort_by(|(left_term, left_count), (right_term, right_count)| {
        right_count
            .cmp(left_count)
            .then_with(|| left_term.cmp(right_term))
    });
    let mut label_terms = ranked_terms
        .into_iter()
        .take(3)
        .map(|(term, _)| term)
        .collect::<Vec<_>>();
    if label_terms.is_empty() {
        label_terms.push(nodes[members[0]].name.to_ascii_lowercase());
    }
    let mut representatives = members.to_vec();
    representatives.sort_by(|&left, &right| {
        weighted_degree[right]
            .total_cmp(&weighted_degree[left])
            .then_with(|| nodes[left].key.cmp(&nodes[right].key))
    });
    let total_weight = internal_weight + external_weight;
    let volume = internal_weight.mul_add(2.0, external_weight);
    DerivedTopologyCommunity {
        id: community,
        label: label_terms.join("-"),
        label_terms,
        members: members
            .iter()
            .map(|&member| nodes[member].key.clone())
            .collect(),
        member_count: members.len(),
        internal_edge_count,
        external_edge_count,
        internal_weight,
        external_weight,
        cohesion: if total_weight > 0.0 {
            internal_weight / total_weight
        } else {
            0.0
        },
        conductance: if volume > 0.0 {
            external_weight / volume
        } else {
            0.0
        },
        representative_symbols: representatives
            .into_iter()
            .take(3)
            .map(|member| nodes[member].key.clone())
            .collect(),
        relationship_kinds,
    }
}

fn semantic_label_terms(value: &str) -> BTreeSet<String> {
    let mut terms = BTreeSet::new();
    let mut current = String::new();
    let mut previous_lowercase = false;
    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            if character.is_ascii_uppercase() && previous_lowercase && !current.is_empty() {
                if current.len() > 1 {
                    terms.insert(std::mem::take(&mut current));
                } else {
                    current.clear();
                }
            }
            current.push(character.to_ascii_lowercase());
            previous_lowercase = character.is_ascii_lowercase();
        } else {
            if current.len() > 1 {
                terms.insert(std::mem::take(&mut current));
            } else {
                current.clear();
            }
            previous_lowercase = false;
        }
    }
    if current.len() > 1 {
        terms.insert(current);
    }
    terms
}

fn derived_topology_lineage(
    previous: &DerivedTopologyArtifact,
    current: &[DerivedTopologyCommunity],
) -> DerivedTopologyLineage {
    let previous_members = previous
        .communities
        .iter()
        .map(|community| {
            (
                community.id,
                community.members.iter().cloned().collect::<BTreeSet<_>>(),
            )
        })
        .collect::<BTreeMap<_, _>>();
    let overlaps = current
        .iter()
        .map(|community| {
            let members = community.members.iter().cloned().collect::<BTreeSet<_>>();
            let prior = previous_members
                .iter()
                .filter_map(|(&id, previous)| {
                    let overlap = members.intersection(previous).count();
                    (overlap > 0).then_some((id, overlap))
                })
                .collect::<Vec<_>>();
            (community.id, prior)
        })
        .collect::<Vec<_>>();
    let mut prior_uses = BTreeMap::<usize, usize>::new();
    for (_, prior) in &overlaps {
        for (id, _) in prior {
            *prior_uses.entry(*id).or_default() += 1;
        }
    }
    let communities = overlaps
        .iter()
        .map(|(community, prior)| {
            let status = match prior.len() {
                0 => CommunityLineageStatus::New,
                1 if prior_uses.get(&prior[0].0).copied().unwrap_or_default() > 1 => {
                    CommunityLineageStatus::Split
                }
                1 => CommunityLineageStatus::Continued,
                _ => CommunityLineageStatus::Merged,
            };
            CommunityLineage {
                community: *community,
                status,
                previous_communities: prior.iter().map(|(id, _)| *id).collect(),
                overlap_count: prior.iter().map(|(_, count)| count).sum(),
            }
        })
        .collect();
    let retained = overlaps
        .iter()
        .flat_map(|(_, prior)| prior.iter().map(|(id, _)| *id))
        .collect::<BTreeSet<_>>();
    DerivedTopologyLineage {
        previous_generation: previous.source.generation,
        previous_input_digest: previous.source.input_digest.clone(),
        communities,
        removed_communities: previous_members
            .keys()
            .filter(|id| !retained.contains(id))
            .copied()
            .collect(),
    }
}

fn derived_topology_changes(
    previous: &DerivedTopologyArtifact,
    current_nodes: &[DerivedTopologyNode],
    current_edges: &[ReferenceEdgeInput],
) -> DerivedTopologyChanges {
    let previous_nodes = previous
        .nodes
        .iter()
        .map(|node| node.input.key.clone())
        .collect::<BTreeSet<_>>();
    let current_nodes = current_nodes
        .iter()
        .map(|node| node.input.key.clone())
        .collect::<BTreeSet<_>>();
    let previous_edges = topology_edge_identities(&previous.edges);
    let current_edges = topology_edge_identities(current_edges);
    DerivedTopologyChanges {
        added_nodes: current_nodes.difference(&previous_nodes).cloned().collect(),
        removed_nodes: previous_nodes.difference(&current_nodes).cloned().collect(),
        added_edges: current_edges.difference(&previous_edges).cloned().collect(),
        removed_edges: previous_edges.difference(&current_edges).cloned().collect(),
    }
}

fn topology_edge_identities(edges: &[ReferenceEdgeInput]) -> BTreeSet<DerivedTopologyEdgeIdentity> {
    edges
        .iter()
        .map(|edge| DerivedTopologyEdgeIdentity {
            source: edge.source.clone(),
            target: edge.target.clone(),
            kind: edge.kind,
        })
        .collect()
}

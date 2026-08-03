impl AgentRenameAuthority {
    pub(crate) fn from_projected_result(result: &Value) -> std::result::Result<Self, String> {
        let preview = result
            .pointer("/plan/preview")
            .cloned()
            .ok_or_else(|| "projected rename plan omitted its exact preview".to_string())?;
        let preview: AgentRenamePreview = serde_json::from_value(preview)
            .map_err(|error| format!("projected rename preview was malformed: {error}"))?;
        preview.validate()?;
        Ok(preview.into_authority())
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if self.target != self.proof.target {
            return Err("rename authority target disagreed with its proof".to_string());
        }
        validate_exact_rename_edits(&self.proof, &self.edits)?;
        let exact_edits = self
            .edits
            .iter()
            .map(|edit| AgentExactFileEdit {
                file_path: &edit.file_path,
                start_offset: edit.start_offset,
                end_offset: edit.end_offset,
                new_text: &edit.new_text,
            })
            .collect::<Vec<_>>();
        validate_exact_file_image_set(&self.file_images, &exact_edits)?;
        Ok(())
    }

    pub(crate) fn new_name(&self) -> &str {
        &self.edits[0].new_text
    }

    pub(crate) fn target_position(&self) -> AgentRenamePosition {
        self.target.position()
    }

    pub(crate) fn file_images(&self) -> &[AgentExactFileImage] {
        &self.file_images
    }

    pub(crate) fn postcondition_authority(&self) -> AgentRenamePostconditionAuthority {
        AgentRenamePostconditionAuthority {
            proof: self.proof.clone(),
            edits: self.edits.clone(),
            images: self.file_images.clone(),
        }
    }

    pub(crate) fn minimum_postcondition_generation(&self) -> u64 {
        self.proof.required_generation.0
    }

    pub(crate) fn validate_postcondition_evidence(
        &self,
        result: &AgentRenamePostconditionEvidence,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        let adjusted = adjusted_rename_edit_ranges(&self.edits)?;
        let declaration_edit = self
            .edits
            .iter()
            .find(|edit| {
                edit.file_path == self.proof.target.declaration_file
                    && edit.start_offset == self.proof.target.declaration_start_offset
            })
            .ok_or_else(|| {
                "rename postcondition authority lost its declaration edit".to_string()
            })?;
        let declaration_range = adjusted
            .get(&(
                declaration_edit.file_path.clone(),
                declaration_edit.start_offset,
                declaration_edit.end_offset,
            ))
            .ok_or_else(|| "rename declaration range was not adjusted".to_string())?;
        let mut expected_target = self.proof.target.clone();
        expected_target.fq_name = renamed_postcondition_fq_name(
            &expected_target.fq_name,
            &declaration_edit.new_text,
        );
        expected_target.declaration_start_offset = declaration_range.0;
        if result.resulting_target != expected_target
            || result.evidence != self.proof.evidence
            || result.evidence.validate()? != result.occurrences.len()
        {
            return Err(
                "rename postcondition changed its resulting identity or complete cardinality"
                    .to_string(),
            );
        }
        let expected_ranges = self
            .proof
            .occurrences
            .iter()
            .map(|occurrence| occurrence.reference.location.source_range_key())
            .map(|key| {
                adjusted
                    .get(&key)
                    .map(|range| (key.0, range.0, range.1))
                    .ok_or_else(|| {
                        "rename postcondition authority dropped one occurrence edit".to_string()
                    })
            })
            .collect::<std::result::Result<BTreeSet<_>, _>>()?;
        let mut observed_ranges = BTreeSet::new();
        for occurrence in &result.occurrences {
            if occurrence.resolved_target != result.resulting_target
                || !occurrence.reference.location.is_valid()
                || !occurrence.reference.containing_symbol.is_valid()
                || occurrence.provenance != AgentExactRenameOccurrenceProvenance::Compiler
                || !observed_ranges.insert(occurrence.reference.location.source_range_key())
            {
                return Err(
                    "rename postcondition occurrence evidence was malformed or rebound"
                        .to_string(),
                );
            }
        }
        if observed_ranges != expected_ranges {
            return Err(
                "rename postcondition occurrence ranges changed from the exact authority"
                    .to_string(),
            );
        }
        Ok(())
    }
}

type RenameSourceRangeKey = (String, u32, u32);
type RenameAdjustedRange = (u32, u32);
type AdjustedRenameRanges = BTreeMap<RenameSourceRangeKey, RenameAdjustedRange>;

fn adjusted_rename_edit_ranges(
    edits: &[AgentRenamePreviewEdit],
) -> std::result::Result<AdjustedRenameRanges, String> {
    let mut by_file = BTreeMap::<&str, Vec<&AgentRenamePreviewEdit>>::new();
    for edit in edits {
        by_file.entry(&edit.file_path).or_default().push(edit);
    }
    let mut adjusted = BTreeMap::new();
    for (_, mut file_edits) in by_file {
        file_edits.sort_by_key(|edit| edit.start_offset);
        let mut delta = 0i64;
        for edit in file_edits {
            let start = i64::from(edit.start_offset) + delta;
            let replacement_length = i64::try_from(edit.new_text.encode_utf16().count())
                .map_err(|_| "rename replacement length overflowed".to_string())?;
            let end = start + replacement_length;
            if start < 0 || end < start || end > i64::from(i32::MAX) {
                return Err("rename adjusted range overflowed".to_string());
            }
            adjusted.insert(
                (edit.file_path.clone(), edit.start_offset, edit.end_offset),
                (start as u32, end as u32),
            );
            delta += replacement_length
                - (i64::from(edit.end_offset) - i64::from(edit.start_offset));
        }
    }
    Ok(adjusted)
}

fn renamed_postcondition_fq_name(old: &str, new_name: &str) -> String {
    old.rsplit_once('.')
        .map_or_else(|| new_name.to_string(), |(owner, _)| format!("{owner}.{new_name}"))
}

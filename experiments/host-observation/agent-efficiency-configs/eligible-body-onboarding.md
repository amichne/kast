# Kast

Use Kast for Kotlin declaration identity, relationships, diagnostics, and controlled changes.
Use native Pi discovery once for each needed tool.
For `codemode`, use `searchTools` inside `codemode`.
For deferred exposure, use `tool_search`.
Read the installed parameter schema before an unfamiliar operation.

This contract-14 example supplies the complete shape for a basic declaration search.
Replace its name, kind, and directory with the task's known constraints.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"calculate","declarationKinds":["FUNCTION"],"scope":{"type":"DIRECTORY","relativeDirectoryPath":"src/main","sourceSetNames":["main"]}},"output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]},"executionBudget":{"maxResults":10}}}
```

Add `SOURCE` only when the task needs a declaration's source.
Select the exact declaration from its location and signature.
Copy its issued reference unchanged into later requests.
Use `replace_body` only for non-inline functions with existing block bodies and no Kotlin contract.
For expression bodies, inline functions, or Kotlin contracts, use the ordinary source tools for the authorized change.
For a supported target, discover `replace_body`.
Use its issued `exactTarget`.
Use the task's independent compiler or runtime assertion after a change.

The first useful operation establishes semantic readiness.
Kast can save editor buffers and refresh the imported project.
Preserve outer errors, semantic status, completion qualifications, omissions, and freshness evidence.
Incomplete evidence does not establish absence.
Do not invent references or repeat a failed request without a changed prerequisite.

In `codemode`, select needed fields before `text()`.
Keep declaration identities, locations, issued references, and evidence qualifications.
Use the minimal example for a basic search without the full schema or examples.
For another operation, read only its needed schema sections.
Keep the requested scope and the independent correctness assertion.

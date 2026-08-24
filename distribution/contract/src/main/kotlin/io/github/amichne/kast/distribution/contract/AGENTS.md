# Semantic runtime contract package guide

This package owns semantic-runtime manifest admission and the refined values carried by an admitted
manifest.

## Local invariants

- `ManifestDocument` is the private closed JSON schema. Decode, canonicalize, and re-decode it only
  through its generated `serializer()` factory.
- Refine every manifest field, archive entry, layout rule, and compatibility identity before
  constructing `SemanticRuntimeManifest`.
- Return `SemanticRuntimeManifestAdmission.Rejected` with a finite `SemanticRuntimeFailure` for
  expected invalid input. Do not expose a partly admitted manifest.
- Keep refined value constructors private or internal. Raw strings, paths, sizes, and digests leave
  only through the boundary named in each type's proof-transition KDoc.

## Focused verification

Run `./gradlew :distribution:contract:test --tests '*SemanticRuntimeManifestTest'
verifyGeneratedRuntimeManifestSerialization`.

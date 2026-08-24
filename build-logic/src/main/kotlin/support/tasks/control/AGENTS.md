# Control metadata build tasks

This package subtree owns generic build-only projection tasks for the staged
control product. Product modules supply typed inputs or process output; these
tasks alone write generated metadata files.

- GenerateControlMetadataTask copies the operation-registry projection
  byte-for-byte and renders its fixed runtime/package metadata documents through generated
  kotlinx serializers.
- WriteJavaProcessOutputTask captures deterministic JVM standard output
  atomically and must not interpret product-domain values.
- WriteProcessOutputTask captures one declared executable's standard output atomically without
  storing Gradle script closures in the execution task.
- WriteProtocolSchemaVersionsTask and WriteSourceIndexSchemaVersionTask emit checked version
  metadata from declared Gradle inputs.

Run the focused task tests, then ./gradlew generateKastControlMetadata.

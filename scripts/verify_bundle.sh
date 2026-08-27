#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
python3 "$root/scripts/verify_bundle.py"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
kotlinc \
  "$root/build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/proof/TaskProofProtocol.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/proof/TaskProofReceipt.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/proof/TaskProofReceiptRefinement.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/projection/ValidatedProgramProjection.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/ProgramAuthorityGeneration.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/ProgramAuthorityModel.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramFoundation.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM0M1.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/model/program/KastVfsPassiveProgramTasksM3M5.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/KastVfsPassiveReusedIndexProgram.kt" \
  "$root/build-logic/src/main/kotlin/support/delivery/ProgramMain.kt" \
  -include-runtime -d "$temporary/program.jar"
java -jar "$temporary/program.jar" "$temporary/program.json" "$temporary/requirements.json"
cmp "$temporary/program.json" "$root/gradle/delivery/kast-vfs-passive-reused-index-program.json"
cmp "$temporary/requirements.json" "$root/gradle/delivery/kast-vfs-passive-requirements.json"
echo "kotlin-projection: deterministic"

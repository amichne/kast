#!/usr/bin/env python3
"""Verify KVP-024 authority without assuming its product exists."""

import json
import pathlib


REPORT_PATH = "ide-plugin/build/reports/KVP-024-endpoint.json"
RECEIPT_PATH = "build/reports/delivery/receipts/KVP-024-COMPLETE.receipt.json"
RECEIPT_ROOT = (
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
    "plugin/project/epoch/model/freshness/singleflight/revalidation/dispatch/"
)


def required_text(root, relative_path):
    path = root / relative_path
    assert path.is_file(), relative_path
    return path.read_text()


def verify_kvp024_delivery(root, program, requirements, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-024")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-013", "KVP-023"],
    }
    assert task["authorities"] == ["IDE_ENDPOINT"]
    assert task["publicInterface"] == "ReadyIdeEndpoint"
    assert task["provesRequirements"] == [
        "KVP-REQ-005",
        "KVP-REQ-017",
        "KVP-REQ-019",
    ]
    assert task["outputs"] == [{
        "description": (
            "One exact endpoint becomes reachable only after complete runtime construction."
        ),
        "id": "kvp.024.proof",
        "kind": "PROOF_ARTIFACT",
        "path": REPORT_PATH,
    }]
    assert task["red"]["command"] == (
        './gradlew :ide-plugin:test --tests "*IdeEndpointPublicationNegativeTest"'
    )
    assert task["green"]["command"] == (
        './gradlew :ide-plugin:test --tests "*IdeEndpointPublicationTest"'
    )
    assert task["completionReceipt"] == {
        "outputPath": RECEIPT_PATH,
        "receiptId": "KVP-024-COMPLETE",
        "requiredDependencyReceipts": ["KVP-013-COMPLETE", "KVP-023-COMPLETE"],
        "requiredGateIds": ["KVP-024-GREEN", "KVP-024-RED"],
    }

    expected_reads = {
        "AGENTS.md",
        "settings.gradle.kts",
        "gradle/libs.versions.toml",
        "ide-plugin",
        "indexer/src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt",
        "indexer/src/main/resources/META-INF/plugin.xml",
        "runtime/ide-read",
        "workspace/contract",
        "workspace/intellij-read",
        "protocol/contract",
        "protocol/wire",
        "build-logic/src/main/kotlin/support/architecture",
        "build-logic/src/test/kotlin/support/architecture",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "gradle/architecture",
        "gradle/delivery",
        "docs/AGENTS.md",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp024_delivery.py",
    }
    expected_writes = {
        "AGENTS.md",
        "ide-plugin/AGENTS.md",
        "ide-plugin/build.gradle.kts",
        "ide-plugin/src/main/kotlin",
        "ide-plugin/src/main/resources",
        "ide-plugin/src/test",
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/AGENTS.md",
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/IdeEndpointLocation.kt",
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/AGENTS.md",
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/IdeEndpointLocationTest.kt",
        "build-logic/src/main/kotlin/support/architecture/ArchitectureModel.kt",
        "build-logic/src/main/kotlin/support/architecture/IdeReadFirewall.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/AGENTS.md",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt",
        "build-logic/src/main/kotlin/support/architecture/validation/AGENTS.md",
        "build-logic/src/main/kotlin/support/architecture/validation/ArchitecturePolicyValidator.kt",
        "build-logic/src/main/kotlin/support/architecture/validation/ModulePolicyValidator.kt",
        "build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt",
        "build-logic/src/test/kotlin/support/architecture/policy/KastCleanSlatePolicyTest.kt",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md",
        RECEIPT_ROOT.split("project/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("epoch/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("model/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("freshness/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("singleflight/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("revalidation/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("dispatch/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT + "AGENTS.md",
        RECEIPT_ROOT + "Kvp023ReceiptRegistration.kt",
        RECEIPT_ROOT + "endpoint",
        "gradle/architecture/kast-architecture-policy.json",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp024_delivery.py",
    }
    assert set(task["allowedReads"]) == expected_reads
    assert set(task["allowedWrites"]) == expected_writes
    assert "settings.gradle.kts" not in expected_writes
    for forbidden_write_root in (
        "indexer/",
        "runtime/ide-read",
        "workspace/",
    ):
        assert not any(path.startswith(forbidden_write_root) for path in expected_writes)
    assert not any(
        path.startswith("protocol/") and not path.startswith("protocol/wire/")
        for path in expected_writes
    )

    section = normative_plan.split(
        "### KVP-024: Publish the exact-root project endpoint\n",
        maxsplit=1,
    )[1].split("\n### KVP-025:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-013`, `KVP-023`." in section
    assert (
        "**Allowed reads.** "
        + ", ".join(f"`{path}`" for path in task["allowedReads"])
        + "."
    ) in section
    assert (
        "**Allowed writes.** "
        + ", ".join(f"`{path}`" for path in task["allowedWrites"])
        + "."
    ) in section
    assert f"**Program fingerprint:** `{program['programFingerprint']}`" in normative_plan

    gates = {
        gate["id"]: gate
        for gate in program["gateGraph"]
        if gate["taskId"] == "KVP-024"
    }
    assert set(gates) == {"KVP-024-COMPLETE-GATE", "KVP-024-GREEN", "KVP-024-RED"}
    direct_receipts = {"KVP-013-COMPLETE", "KVP-023-COMPLETE"}
    assert set(gates["KVP-024-RED"]["dependsOnReceiptIds"]) == direct_receipts
    assert set(gates["KVP-024-GREEN"]["dependsOnReceiptIds"]) == direct_receipts | {
        "KVP-024-RED-RECEIPT",
    }
    assert set(gates["KVP-024-COMPLETE-GATE"]["dependsOnReceiptIds"]) == (
        direct_receipts | {"KVP-024-GREEN-RECEIPT", "KVP-024-RED-RECEIPT"}
    )

    assert requirements["programFingerprint"] == program["programFingerprint"]
    traced = {
        entry["requirementId"]: entry
        for entry in requirements["entries"]
        if entry["requirementId"] in task["provesRequirements"]
    }
    assert set(traced) == set(task["provesRequirements"])
    for entry in traced.values():
        assert "KVP-024" in entry["implementationTaskIds"]
        assert {"KVP-024-RED", "KVP-024-GREEN"} <= set(entry["enforcementGateIds"])

    architecture = json.loads(required_text(
        root,
        "gradle/architecture/kast-architecture-policy.json",
    ))
    ide_plugin = next(
        module for module in architecture["modules"]
        if module["id"] == "IDE_PLUGIN"
    )
    assert ide_plugin["projectPath"] == ":ide-plugin"
    assert ide_plugin["role"] == "IDE_READ_ONLY"
    assert ide_plugin["allowedEffects"] == [
        "ENDPOINT_DESCRIPTOR_WRITE",
        "INTELLIJ_PLATFORM",
        "UDS_BIND",
    ]
    assert ide_plugin["allowedProjectDependencies"] == [
        ":protocol:contract",
        ":protocol:wire",
        ":runtime:ide-read",
        ":workspace:intellij-read",
    ]
    policy = required_text(
        root,
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
    )
    plugin_policy = policy.split(
        "ideRead(\n            ModuleId.IDE_PLUGIN,",
        maxsplit=1,
    )[1].split("\n        ),", maxsplit=1)[0]
    for dependency in (
        "ModuleId.PROTOCOL_CONTRACT",
        "ModuleId.PROTOCOL_WIRE",
        "ModuleId.RUNTIME_IDE_READ",
        "ModuleId.WORKSPACE_INTELLIJ_READ",
    ):
        assert dependency in plugin_policy
    assert "ForbiddenEffect.UDS_BIND" in plugin_policy
    assert "ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE" in plugin_policy

    location = required_text(
        root,
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/IdeEndpointLocation.kt",
    )
    assert "class IdeEndpointLocation private constructor" in location
    assert "IdeEndpointDescriptorPath" in location

    bundle = required_text(root, "scripts/verify_bundle.py")
    assert "from verify_kvp024_delivery import verify_kvp024_delivery" in bundle
    assert "verify_kvp024_delivery(root, program, requirements, normative_plan)" in bundle

    endpoint_receipt_root = RECEIPT_ROOT + "endpoint/"
    receipt_files = {
        "AGENTS.md",
        "Kvp024DescriptorBindings.kt",
        "Kvp024EndpointPublicationReport.kt",
        "Kvp024GateExecution.kt",
        "Kvp024MutationProof.kt",
        "Kvp024ReceiptDependencies.kt",
        "Kvp024ReceiptProgression.kt",
        "Kvp024ReceiptRegistration.kt",
        "Kvp024ReceiptTasks.kt",
        "Kvp024ReportTasks.kt",
    }
    for filename in receipt_files:
        required_text(root, endpoint_receipt_root + filename)

    report = required_text(
        root,
        endpoint_receipt_root + "Kvp024EndpointPublicationReport.kt",
    )
    for claim in (
        "IDE_ENDPOINT",
        "ReadyIdeEndpoint",
        "PROJECT",
        "UNIX_DOMAIN_SOCKET",
        "kast.ide.endpoint.v2",
        "length-prefixed-json-v1",
        "PREPARED",
        "SOCKET_BOUND",
        "READY",
        "UDS_BIND",
        "ENDPOINT_DESCRIPTOR_WRITE",
        "endpointLimitPerProject = 1",
        "socketBindLimitPerEndpoint = 1",
        "descriptorPublicationLimitPerEndpoint = 1",
        "OCCUPIED_NON_SOCKET_PATH",
        "REACHABLE_OR_OCCUPIED_SOCKET",
        "NO_MOVE_FALLBACK",
        "DELETE_UNOWNED_PATH",
    ):
        assert claim in report, claim
    dependencies = required_text(
        root,
        endpoint_receipt_root + "Kvp024ReceiptDependencies.kt",
    )
    assert dependencies.index("KVP_013_COMPLETE") < dependencies.index("KVP_023_COMPLETE")
    descriptor_bindings = required_text(
        root,
        endpoint_receipt_root + "Kvp024DescriptorBindings.kt",
    )
    for field in (
        "SCHEMA",
        "CANONICAL_ROOT",
        "HOST_KIND",
        "PROCESS_ID",
        "IDE_BUILD",
        "KOTLIN_PLUGIN_BUILD",
        "KAST_PLUGIN_VERSION",
        "RUNTIME_PROTOCOL_IDENTITY",
        "OPERATION_REGISTRY_DIGEST",
        "WIRE_SCHEMA_DIGEST",
        "SOCKET_PATH",
        "FRAMING",
        "RUNTIME_EPOCH",
        "CAPABILITIES",
    ):
        assert f"Kvp024DescriptorField.{field}" in descriptor_bindings

    build_script = required_text(root, "ide-plugin/build.gradle.kts")
    assert 'id("kast.role.ide-read-only")' in build_script
    for marker in (
        "generateIdeEndpointPublicationReport",
        "verifyIdeEndpointPublicationReportNegative",
        "verifyIdeEndpointPublicationNegative",
        "verifyIdeEndpointPublication",
        "kast.ide.endpoint.report",
        "KVP-024-endpoint.json",
    ):
        assert marker in build_script
    gate_source = required_text(root, endpoint_receipt_root + "Kvp024GateExecution.kt")
    assert "*IdeEndpointPublicationNegativeTest" in gate_source
    assert "*IdeEndpointPublicationTest" in gate_source
    assert "tasks.named<Test>(\"test\")" in build_script
    parent_registration = required_text(
        root,
        RECEIPT_ROOT + "Kvp023ReceiptRegistration.kt",
    )
    assert "registerKvp024ReceiptProgression(program)" in parent_registration

    product_root = root / "ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/endpoint"
    product_files = {
        "AGENTS.md",
        "ReadyIdeEndpoint.kt",
        "PreparedIdeEndpoint.kt",
        "IdeEndpointPreparation.kt",
        "IdeEndpointPublication.kt",
        "IdeEndpointPublicationFailure.kt",
        "IdeEndpointService.kt",
    }
    present_product_files = {
        path.name for path in product_root.iterdir()
    } if product_root.is_dir() else set()
    assert not present_product_files or product_files <= present_product_files
    test_root = root / "ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/endpoint"
    test_files = {
        "AGENTS.md",
        "IdeEndpointPublicationNegativeTest.kt",
        "IdeEndpointPublicationTest.kt",
    }
    present_test_files = {
        path.name for path in test_root.iterdir()
    } if test_root.is_dir() else set()
    assert bool(present_product_files) == bool(present_test_files)
    if present_product_files:
        assert test_files <= present_test_files
        product_source = "\n".join(
            required_text(root, f"ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/endpoint/{name}")
            for name in sorted(product_files - {"AGENTS.md"})
        )
        for marker in (
            "ReadyIdeEndpoint",
            "PreparedIdeEndpoint",
            "IdeEndpointPublicationFailure",
            "IdeEndpointService",
        ):
            assert marker in product_source
    product_dependencies = tuple(
        f'implementation(project("{dependency}"))'
        for dependency in (
        ":protocol:wire",
        ":runtime:ide-read",
        ":workspace:intellij-read",
        )
    )
    if present_product_files:
        assert all(dependency in build_script for dependency in product_dependencies)
    else:
        assert not any(dependency in build_script for dependency in product_dependencies)
    assert 'implementation(project(":runtime:composition"))' not in build_script
    assert not (root / REPORT_PATH).exists()
    assert not (root / RECEIPT_PATH).exists()


if __name__ == "__main__":
    repository = pathlib.Path(__file__).resolve().parents[1]
    generated_program = json.loads(required_text(
        repository,
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
    ))
    generated_requirements = json.loads(required_text(
        repository,
        "gradle/delivery/kast-vfs-passive-requirements.json",
    ))
    plan = required_text(
        repository,
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
    )
    verify_kvp024_delivery(repository, generated_program, generated_requirements, plan)
    print("KVP-024 delivery contract: valid")

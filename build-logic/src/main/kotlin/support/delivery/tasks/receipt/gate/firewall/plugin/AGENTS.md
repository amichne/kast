# KVP-010, KVP-012, and KVP-013 plugin receipt guide

This directory owns KVP-010's standalone-plugin progression, KVP-012's host-compatibility
progression, and delegates KVP-013 descriptor progression to `endpoint/`.

- Execute only the two canonical standalone-plugin commands as fixed Gradle argument vectors.
- Admit KVP-009 completion directly before issuing either gate receipt.
- Decode the closed KVP-010 report through its generated serializer, re-observe the physical ZIP,
  and bind the report, archive, descriptor owner, and every payload JAR.
- Derive completion only from the admitted predecessor, RED, and GREEN receipts.
- Execute only KVP-012's declared negative and positive compatibility-test argument vectors.
- Admit KVP-002 and KVP-010 directly for every KVP-012 gate, independently refine the generated
  compatibility report against physical registry and canonical wire bytes, and bind the report.

Keep raw process arguments, JSON, and ZIP bytes at their outer Gradle boundaries. Expected command,
report, archive, and receipt failures remain finite typed data until the task renders them.

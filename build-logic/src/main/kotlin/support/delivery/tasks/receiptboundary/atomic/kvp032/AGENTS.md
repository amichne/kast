# KVP-032 atomic proof policy

This package owns the sole graph-derived KVP-032 packet, named static-safety evidence, dependency
admission, write-scope enforcement, content-scoped reuse, and v2 completion receipt.

- Preserve the pinned KVP-009 and KVP-023 legacy receipts without regenerating the admitted prefix.
- Re-admit KVP-011, KVP-027, and current exact-head KVP-031 receipts and physical outputs.
- Derive commands, cases, forbidden work, paths, and receipt identity only from the Kotlin graph.
- Derive physical write-ownership anchors from the canonical hosted production-composition batch;
  endpoint, runtime/workspace, and plugin-classpath companion paths remain owned by their tasks.
- Observe implementation commits only after that batch's graph-admitted ready frontier.
- Execute the ASM forbidden-call and archive-classpath fixtures before composing the module,
  compiled-effect, firewall, and transitive archive reports. Never use source grep as sole proof.
- Emit exactly one KVP-032 completion receipt; no RED/GREEN receipt pair is created.

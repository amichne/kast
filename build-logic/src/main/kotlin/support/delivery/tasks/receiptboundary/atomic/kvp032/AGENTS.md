# KVP-032 atomic proof policy

This package owns the sole graph-derived KVP-032 packet, named static-safety evidence, dependency
admission, write-scope enforcement, content-scoped reuse, and v2 completion receipt.

- Preserve KVP-009 through the exact pinned KVP-010 successor receipt that already admitted its
  digest; the overwritten KVP-009 file is not a recovery authority. Preserve the pinned KVP-023
  legacy receipt without regenerating the admitted prefix.
- Re-admit KVP-011, KVP-027, and current exact-head KVP-031 receipts and physical outputs.
- Derive commands, cases, forbidden work, paths, and receipt identity only from the Kotlin graph.
- Derive physical write-ownership anchors from the canonical batch. A later task may own a path
  beneath KVP-032's broad delivery root only through a strictly more-specific graph-declared write
  scope; ignore a mixed checkpoint only when that scope proves successor ownership.
- Observe implementation commits only after that batch's graph-admitted ready frontier.
- Execute the ASM forbidden-call and archive-classpath fixtures before composing the module,
  compiled-effect, firewall, and transitive archive reports. Never use source grep as sole proof.
- Use KVP-032's isolated plugin-layout tasks for that composition; shared KVP-011 case tasks must
  not schedule predecessor proof work inside the KVP-032 command.
- Emit exactly one KVP-032 completion receipt; no RED/GREEN receipt pair is created.

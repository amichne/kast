# Automatic invocation retention decisions

Base: main `059d7cdc57d45a5404c3a31d8d6dcaa7e3f00dde`.

The sealed native Pi fixture rejects unfinished task storage at 3,597,622
conservative bytes against a remaining allowance of 3,417,074 bytes. Its known
minimum is 194 of 1,001 references. That observation does not contain the exact
invocation facts/issued-state/request decomposition. The global store gauge is
not a substitute. No native replay or provider inference is part of this work.

The existing pure dense fixture uses different grants. On unchanged main it
retains 393 rows and rejects a 789,650-byte checkpoint against 685,851 bytes.
Facts retain 19,921,968 bytes; final invocation charge is 31,609,640 bytes.
The existing inventory identity and transaction proof remains authoritative.

`CanonicalQueryProtocol.execute` runs internal pages with Immediate publication.
Every consumed checkpoint becomes a stored immutable replay page. That is real
retained state and its conservative charge is legitimate. Automatic invocation
tokens have not been returned to the caller; only final resumable progress may
escape. The bounded source option is to end the private replay lifetime after
successful consumption, preserving the final checkpoint and every public replay.

The implementation must prove private allocation ownership before release.
Existing ready checkpoints can be reused, so merely observing a token in an
automatic page is insufficient. A private checkpoint must also be excluded from
public reissuance until it is explicitly published as final progress. Reservation
and publication rollback stay in their existing owner; expiry must not extend
authority. Non-advancing semantic checkpoints remain checked independently.

Alternatives: lowering multipliers without allocation evidence would weaken the
bound; raising quotas would hide finite demand; deduplicating equal detached
objects would lose true allocation identity. This change will do none of those.
Dropping semantic checkpoint history would weaken the non-advancing proof and is
outside this slice. Public hosted page fitting and client delivery belong to
separate owners.

The tracking draft characterizes three pure pages and records their retained
replay entries. Before/after counters and semantic identity, rollback,
cancellation, repeated reuse, public replay and unrelated-owner controls will
qualify the source option. Accounted bytes are not heap or latency measurements.

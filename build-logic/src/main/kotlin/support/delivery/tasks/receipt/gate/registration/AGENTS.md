# Receipt registration guide

This directory owns compiled Gradle registration for exact-head receipt progression.

- `DeliveryReceiptRegistrationModel.kt` refines one validated program task into its exact gates,
  receipt outputs, proof output, and canonical input digests.
- `ReceiptProgressionRegistration.kt` configures typed receipt owners through KVP-010 and KVP-012
  through KVP-014, and returns the task identities whose generic placeholders must be replaced.
- Task-specific registration files wire fixed record, derive, and re-admission task types without
  parsing shell commands.

Keep registration declarative. Execution, admission, report decoding, and receipt issuance belong
to the task-specific owners adjacent to this directory.

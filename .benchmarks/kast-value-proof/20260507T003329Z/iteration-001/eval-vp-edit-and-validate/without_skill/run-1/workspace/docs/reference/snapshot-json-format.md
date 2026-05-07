# Snapshot JSON format

`Namespace.toJson()` exports a namespace snapshot as one JSON object with an
optional `meta` section and a required `flags` array.

## Top-level shape

This is the current conceptual wire shape.

```json
{
  "meta": {
    "version": "v1",
    "generatedAtEpochMillis": 1735689600000,
    "source": "control-plane"
  },
  "flags": [
    {
      "key": "feature::checkout::productEligibility",
      "defaultValue": {
        "type": "ENUM",
        "value": "ELIGIBLE",
        "enumClassName": "com.example.ProductEligibility"
      },
      "salt": "v1",
      "isActive": true,
      "rampUpAllowlist": [],
      "rules": []
    }
  ]
}
```

`meta` is omitted when all metadata fields are null.

## Flag entries

Each entry in `flags` represents one feature definition for the namespace.

| Field | Meaning |
| --- | --- |
| `key` | Serialized `FeatureId` |
| `defaultValue` | Typed value envelope for the flag's default |
| `salt` | Stable bucketing salt |
| `isActive` | Whether the flag is active |
| `rampUpAllowlist` | Stable IDs that bypass ramp-up gating |
| `rules` | Serialized rule list in snapshot order |

## Value envelope shapes

`defaultValue` and rule `value` fields use a typed envelope.

| `type` | Additional fields |
| --- | --- |
| `BOOLEAN` | `value: true` or `false` |
| `STRING` | `value: "..."` |
| `INT` | `value: 42` |
| `DOUBLE` | `value: 42.5` |
| `ENUM` | `value`, `enumClassName` |
| `DATA_CLASS` | `value`, `dataClassName` |
| `KONSTRAINED_PRIMITIVE` | `value`, `konstrainedClassName` |

## Rule entries

Each rule entry can carry these fields.

| Field | Meaning |
| --- | --- |
| `value` | Typed envelope for the rule value |
| `type` | Static or contextual rule value type |
| `ruleId` | Optional serialized rule identifier |
| `rampUp` | Rollout percentage as a `Double` |
| `rampUpAllowlist` | Stable IDs that bypass rule ramp-up gating |
| `note` | Optional human note |
| `locales` | Allowed locale identifiers |
| `platforms` | Allowed platform identifiers |
| `versionRange` | Serialized version-range object |
| `axes` | Map of axis ID to allowed value IDs |

## Next steps

If you want to inspect how `explain(...)` describes a decision rather than how
`toJson()` serializes a namespace, open the diagnostics page.

- [Open evaluation diagnostics](evaluation-diagnostics.md)


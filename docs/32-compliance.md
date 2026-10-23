# 32. Retention, legal hold and erasure

Compliance workflows need three things beyond a plain TTL: different retention
windows for different data, a way to freeze records while a legal matter is
open, and a way to delete one subject's events without destroying the audit
trail that proves the deletion happened. The Room module ships all three; the
APIs operate on the indexed columns, so they keep working with encrypted
payloads ([31](31-encryption-at-rest.md)) and require no decryption.

Everything here applies to the shipped `ilogger_events` table. File sinks are
append-only: age their contents out with rotation or rewrite an encrypted
archive before deleting it — see the limitations at the end.

## Retention policies

`RoomLogRetention` (see [19](19-room-integration.md)) is one window for every
row. When categories and entities have different legal lifetimes, use
`RoomLogRetentionPolicy`:

```kotlin
val policy = RoomLogRetentionPolicy(
    defaultKeepMillis = 30 * 86_400_000L,
    rules = listOf(
        RoomLogRetentionRule.days(365, category = "security"),
        RoomLogRetentionRule.days(90, entity = "Order"),
        RoomLogRetentionRule.days(7, category = "debug", entity = "HttpCall"),
    ),
)
```

Resolution is by specificity, per row:

| Match | Keep window |
|---|---|
| Rule with both category and entity | that rule |
| Rule with entity only | that rule |
| Rule with category only | that rule |
| No rule | `defaultKeepMillis` |
| No rule and no default | forever |

A rule must select a category, an entity, or both. Rules with the same selector
pair are rejected, negative windows are rejected, and a policy with neither a
default nor a rule is rejected — there is no silent "delete everything"
configuration. `minimumKeepMillis()` is the smallest window any row can
resolve to and drives the sweep's candidate query.

## Hold-aware sweeps

`RoomLogRetentionEnforcer` applies a policy, honors legal holds, and can hand
each batch to an archive before deleting it:

```kotlin
val enforcer = RoomLogRetentionEnforcer(
    dao = db.logDao(),
    policy = policy,
    holds = holds,
    beforePurge = { rows -> archive.append(rows) },   // optional
)
RoomLogRetentionScheduler(enforcer, sweepEveryMillis = 3_600_000).start(appScope)
```

The sweep pages expired rows oldest-first with a keyset cursor, so rows that a
hold protects never stall the scan, resolves each row through the policy, and
deletes the rest in chunks of 500. `sweep()` returns
`RoomLogRetentionSweep(removed, held)`.

`beforePurge` receives exactly the rows about to be deleted — the export
happens before the delete, in the same sweep. If the hook throws, that chunk is
not deleted and the exception reaches the scheduler's `onError`; the next sweep
tries again. Use it for cold-storage export, a checksum manifest, or both.

`RoomLogRetentionScheduler(enforcer, intervalMillis)` is independent of writes:
the first sweep runs immediately, then one per interval, and a suspended
process catches up as soon as it resumes. The write-triggered path
(`roomLogs(..., retention = ...)`) accepts a `holds` registry too — when one is
supplied the target performs the same row-wise sweep instead of
`purgeBefore`. Per-category policies and the archive hook are scheduler-only.

## Legal holds

A hold is a value matched against indexed columns:

```kotlin
val holds = RoomLogLegalHoldRegistry()
holds.hold(RoomLogLegalHold.Actor("user-42"))
holds.hold(RoomLogLegalHold.Entity("Order", "order-7"))   // one instance
holds.hold(RoomLogLegalHold.Category("security"))
```

| Hold | Protects |
|---|---|
| `Event(eventId)` | one event |
| `Actor(actorId)` | every event attributed to the actor |
| `Entity(entity, entityId?)` | one entity instance, or every event of that entity type |
| `Category(category)` | every event routed to the category |
| `Action(action)` | every event with the action |

Held rows are skipped by both retention sweeps and erasure, and are reported
as `held`/`withheld` rather than silently retained. The registry is
thread-safe and in memory; persist `snapshot()` in your own storage and
re-apply it at startup with `hold(...)` when holds must survive process death.
Release with `release(hold)` or `clear()`.

Holds are evaluated against columns, not payloads. A hold on an actor therefore
also protects encrypted rows, and a hold on a category is not a substitute for
verifying that the category column is populated.

## Erasure with a deletion audit

```kotlin
val erasure = RoomLogErasure(db.logDao(), holds = holds, payloadCipher = cipher)
val report = erasure.eraseActor(
    actorId = "user-42",
    reason = "GDPR Art. 17 request",
    requestId = "DSAR-2026-118",
)
```

`eraseActor` collects every row with that actor id, and `eraseEntity(entity,
entityId)` every row of one instance. Held rows are kept; the rest are deleted
in chunks. Then exactly one audit event is written:

| Field | Value |
|---|---|
| `action` | `compliance.erasure` (`ROOM_LOG_ERASURE_ACTION`) |
| `category` | `compliance` (`ROOM_LOG_ERASURE_CATEGORY`) |
| `level` | `AUDIT` |
| `actorId` / `entity` | null — the audit event cannot re-identify the subject |
| `metadata.targetKind` | `"actor"` or `"entity"` |
| `metadata.targetHash` | first 16 hex chars of SHA-256 over the erased identifier |
| `metadata.removed` / `metadata.withheld` | counts from the report |
| `metadata.reason` | caller-supplied justification |
| `metadata.requestId` | caller-supplied ticket id, when present |

The audit event carries **no raw identifier and no payload of the erased
rows**. It is written through the same codec and cipher as everything else, so
an encrypted table stays encrypted.

`RoomLogErasureReport.targetHash`, `.removed`, `.withheld` and `.auditEventId`
are returned directly. Repeating an erasure for the same subject removes
nothing new but still records a new audit event — the previous audit row has
no actor/entity link, so it is never a target.

### GDPR-shaped workflow

```kotlin
holds.hold(RoomLogLegalHold.Actor(actorId))                 // 1. freeze on request
// 2. export/archive via the enforcer's beforePurge or an explicit query
val report = erasure.eraseActor(actorId, reason, requestId) // 3. erase + audit
holds.release(RoomLogLegalHold.Actor(actorId))              // 4. release after verification
```

The report is the proof for step 4: it ties the request id to the counts and to
the audit event, whose hash lets you find the record without keeping the
identifier itself.

## Interaction with integrity and encryption

- **Encryption** ([31](31-encryption-at-rest.md)) is transparent: retention and
  erasure filter on plaintext indexed columns, and the audit event is stored
  with the configured `payloadCipher`.
- **Hash chains** ([30](30-integrity.md)) conflict with deletion by design.
  Purging or erasing a row from a chained table breaks verification from that
  point on. Decide per store: keep the chain and erase elsewhere, or store the
  audit trail in a chained table and keep subject data in an erasable table.
  Deletion-audit events themselves are ordinary rows and can be chained.
- **Downstream copies** are out of scope. Exported JSONL, remote sinks and
  backups need their own erasure/expiry story.

## Limits

| Concern | Behavior |
|---|---|
| Scope | Shipped `ilogger_events` table only; file sinks are append-only |
| File sinks | Use rotation (`maxAgeMillis`/`maxFiles`) to age files out, or rewrite before deletion |
| `targetHash` | Pseudonymous, not anonymous — a low-entropy identifier can be brute-forced; use `requestId` for linkage |
| `reason` | Stored verbatim in the audit event — do not put personal data in it |
| Holds | In-memory registry; persistence and restore are the application's job |
| Concurrency | Erasure reads all matching rows before deleting; suppress concurrent writes for a strictly consistent report |
| Encryption keys | Deleting rows does not rotate keys; rotate after erasure if the key itself is compromised |

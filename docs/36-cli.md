# 36. CLI: tail, verify, export, schema, replay

`ilogger-cli` is the operations companion to the library: it reads the files
and databases the runtime writes, verifies tamper-evident chains, converts
shipped events, inspects the generated action registry, and re-emits events
into another sink. It is a plain JVM 17 application — no Android SDK, no
Room runtime, no application classpath.

The CLI is **not published to Maven Central**. Build the distribution from
this repository:

```bash
./gradlew :ilogger-cli:installDist
ilogger-cli/build/install/ilogger/bin/ilogger --help    # Windows: ilogger.bat
```

Or run it through Gradle during development:

```bash
./gradlew :ilogger-cli:run --args="tail audit.jsonl --level WARN"
```

## Conventions

- Options are `--name value` or `--name=value`; a bare `--` ends option
  parsing.
- List options accept repetition or commas: `--level WARN,ERROR`.
- Matching filters are exact, or prefix-based when the pattern ends with `*`
  (`task.*`).
- Exit codes are stable: **0** success, **1** runtime or verification failure,
  **2** usage error (unknown option, bad value, missing argument).
- `tail`, `verify`, `export` and `replay` accept `--decrypt-key` and read
  `ilogenc1:` payloads transparently (see [31](31-encryption-at-rest.md)).

## `tail`

Prints matching events, one per line, in the compact single-line form
(timestamp, level, action, entity, actor, outcome and — when present —
correlation, source, category, failure, metadata and changes).

```bash
ilogger tail audit.jsonl
ilogger tail audit.jsonl --level WARN,ERROR --last 30m
ilogger tail app.jsonl --action 'task.*' --actor u-42
ilogger tail audit.jsonl --since 2026-10-01 --until 2026-10-02 --limit 500
ilogger tail audit.jsonl --follow --poll-millis 500
ilogger tail encrypted.jsonl --decrypt-key prod=file:/run/secrets/audit-aes.key
```

| Option | Meaning |
|---|---|
| `--level L[,L]` | Diagnostic/audit levels (`TRACE`…`ERROR`, `AUDIT`) |
| `--action/--entity/--actor/--category/--source P[,P]` | Field filters; `*` suffix means prefix |
| `--since`, `--until` | Inclusive epoch millis, ISO instant or date (`2026-10-01`) |
| `--last 30m/12h/7d` | Window ending now; conflicts with `--since`/`--until` |
| `--limit N` | Stop after N matching events (also ends `--follow`) |
| `--json` | Print the original canonical JSONL line instead of compact text |
| `--follow` | Keep polling the file for appended lines |
| `--poll-millis N` | Follow poll interval (default 250) |

`--json` prints the line as stored, so stamps, metadata order and payloads
round-trip unchanged. A malformed line fails the command with its 1-based line
number; there is no silent skipping.

## `verify`

Recomputes the hash chain written by `HashChainSink` (see
[30](30-integrity.md)). Without keys it checks only the structure — stamps
present, sequence and links consistent. Pass `--hmac-key` for every key id in
the file to authenticate content.

```bash
ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key
ilogger verify audit.jsonl --hmac-key old=... --hmac-key new=... --json
ilogger verify encrypted.jsonl \
  --decrypt-key prod=file:/run/secrets/audit-aes.key \
  --hmac-key prod=file:/run/secrets/audit.key
```

```json
{"valid":false,"checked":42,"total":90,"mode":"keyed","keys":["prod"],
 "brokenAt":41,"line":42,"reason":"stamp does not match record content (modified, reordered or forged)"}
```

The verdict is a single line. `--json` is designed for CI gates: exit code 1
and `valid: false` mean the file must not be trusted. `line` maps the broken
record back to its physical line; `brokenAt` is the 0-based index in the
decoded event list.

## `export`

Reads JSONL or a Room database and writes CSV (default), a JSON array, or
canonical JSONL.

```bash
ilogger export app.db --format csv --output audit.csv
ilogger export audit.jsonl --action 'task.*' --format json
ilogger export app.db --table ilogger_events --decrypt-key prod=file:/run/secrets/audit-aes.key
```

| Option | Meaning |
|---|---|
| `--from auto\|room\|jsonl` | Input type; `auto` uses the extension (`.db`, `.sqlite`, `.sqlite3`, `.db3`) |
| `--format csv\|json\|jsonl` | Output format |
| `--output PATH` | File instead of stdout (`-` means stdout); parent directories are created |
| `--table NAME` | Room table (default `ilogger_events`); rejected for JSONL input |
| Filters, time window, `--limit`, `--decrypt-key` | Same semantics as `tail` |

CSV is RFC-style quoted and always starts with the header row (see
`EventFormat.csvHeader`): timestamp, level, action, version, outcome, entity,
ids, actor, source, category, correlation, event id, failure, metadata and
changes. A summary goes to stderr (`exported N events (csv) to <destination>`),
so stdout stays pipeable.

### Reading a Room database

The CLI opens the SQLite file with the bundled driver and selects
`payload FROM <table> ORDER BY ts, eventId`. It does not link Room or the
application's generated database class, so it works on a pulled device file or
a server backup. Two caveats:

- **WAL sidecars**: if the database was copied while the app was running in WAL
  mode, copy `app.db`, `app.db-wal` and `app.db-shm` together (or checkpoint
  first). Without the `-wal` file, recently committed events may be missing.
- **Encrypted payloads**: when `RoomLogTarget(payloadCipher = …)` was used, pass
  the same keyring via `--decrypt-key`; SQLCipher-encrypted databases are not
  readable by the CLI — decrypt a SQLCipher dump first.

## `schema`

Inspects the KSP action registry (`META-INF/ilogger/schema.json`, see
[06](06-actions-and-schema-versioning.md)). The manifest normally lives at
`build/generated/ksp/main/resources/META-INF/ilogger/schema.json`, or at the
path configured with `ilogger.schema-output`.

```bash
ilogger schema --manifest build/ilogger/schema.json
ilogger schema build/generated/ksp/main/resources/META-INF/ilogger/schema.json --format json
ilogger schema --manifest schema.json --action 'task.*'
```

The table lists action, version, entity, metadata keys, change fields and the
number of producer sources; the footer reports `formatVersion`, the contract
count and the declared action list. `--format json` emits the manifest
unchanged, or the filtered subset with the original `declared` list. Duplicate
`schemaId`s, non-numeric versions and unknown `formatVersion` values fail with
exit code 1 — this is a build-time drift gate, not a best-effort viewer.

## `replay`

Re-emits filtered events into a sink. Replay bypasses the engine and writes the
decoded events directly, so **ids, timestamps and payloads are preserved** (use
`export` when you want a different representation instead).

```bash
ilogger replay audit.jsonl                                  # compact console lines
ilogger replay audit.jsonl --json                           # console JSONL
ilogger replay audit.jsonl --action task.created --to jsonl:replayed.jsonl
ilogger replay audit.jsonl --decrypt-key old=... \
  --to jsonl:rotated.jsonl --encrypt-key new=... --overwrite
```

| Option | Meaning |
|---|---|
| `--to console` (default) | Compact text, or JSONL with `--json` |
| `--to jsonl:PATH` | Append canonical JSONL to `PATH` (created with parents) |
| `--overwrite` | Truncate the JSONL target instead of appending |
| `--encrypt-key K` | Encrypt the JSONL target with one AES-GCM key |
| `--decrypt-key K` | Keyring for encrypted input |
| `--quiet` | Suppress the stderr summary |

`--overwrite` and `--encrypt-key` are rejected for the console target. With
`--encrypt-key` every written line is an `ilogenc1:` token, so a replay into
`jsonl:` is also the supported **key-rotation path** for a file sink: decrypt
with the old keyring, write with the new key.

## Key material

All key options share one grammar:

```
id=base64:<value> | id=base64url:<value> | id=hex:<value> | id=file:<path>
```

- `id` must match the `keyId` stamped on the records (or the `ilogenc1` token)
  and must not contain `:`.
- Repeat the option to supply a keyring; duplicate ids are rejected.
- `file:` reads raw bytes, which is the recommended form for production keys —
  material then never appears in shell history or process listings.
- Error messages name the id and path, never the material.

## Automation recipes

```bash
# Nightly audit gate: fail the job on tampering
ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key --json

# Daily CSV extract, excluding what must not leave the host
ilogger export app.db --level AUDIT --since "$(date -d yesterday +%F)" \
  --output "audit-$(date +%F).csv"

# Key rotation after a compromise
ilogger replay audit.jsonl --decrypt-key old=file:/keys/old \
  --to jsonl:rotated.jsonl --encrypt-key new=file:/keys/new --overwrite

# Fail when the action registry drifted from the reviewed baseline
diff <(jq -S . audit/schema-baseline.json) \
     <(ilogger schema --format json \
         --manifest build/generated/ksp/main/resources/META-INF/ilogger/schema.json | jq -S .)
```

## Testing and limits

The module's 83 tests cover every command, filter, key encoding, Room read
path, encrypted read, rotation and usage error; line coverage is held at 80%
by `koverVerify` (current baseline ~96.8%). The CLI has no read access to
in-memory sinks, remote spools or platform-specific stores: export those to
JSONL or a Room table first. The optional GUI/Compose viewer mentioned in the
roadmap is not part of this release.

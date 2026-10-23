# 31. Encryption at rest

Logs frequently contain identifiers, amounts and free-form messages that
should not sit in plaintext on disk or in a database file. ILogger ships one
cipher — **AES-GCM** — and two places to apply it: every line of
`TextFileSink` and the `payload` column of the Room target. Both are opt-in;
nothing is encrypted unless you pass a cipher.

```kotlin
val key = EncryptionKey("2026-10", keyBytes)   // 16, 24 or 32 bytes

ILogger.create {
    sink(TextFileSink(file = logsDir.resolve("app.log"), cipher = AesGcmCipher(key)))
}
```

## What is written

Every stored value is a self-describing token:

```text
ilogenc1:<keyId>:<base64url(iv ‖ ciphertext ‖ tag)>
```

| Part | Meaning |
|---|---|
| `ilogenc1:` | Format prefix; changes only on a breaking format revision |
| `keyId` | Id of the key that produced the token, used to pick the right key when reading |
| IV | 12 random bytes, fresh for every record |
| tag | 128-bit GCM authentication tag |

The file sink encrypts the codec's full output line, so a JSONL file becomes
one opaque token per line: ids, timestamps, metadata — everything — is
unreadable without the key. Rotation, gzip and fsync compose unchanged. Tokens
are longer than plaintext, so size-based rotation (`maxBytesPerFile`) fills
files sooner; that is accounted for per record, not per line estimate.

The Room adapter encrypts only the payload column; the indexed columns
(`event_id`, `action`, `timestamp`, …) stay plaintext so queries and
`retention` keep working. See [19](19-room-integration.md) for
`AesGcmRoomPayloadCipher` and the SQLCipher alternative that encrypts the
whole database.

## Keys

`EncryptionKey` validates at construction: non-blank id, no `:` (the token
delimiter), and 16/24/32 key bytes (AES-128/192/256). `AesGcmCipher` reads the
current key through `EncryptionKeyProvider`, a one-method interface, so the
key can come from a secret store, the Android Keystore, or an
in-memory cache refreshed after rotation.

**Never ship default keys.** A key checked into the repository or hard-coded
in the binary encrypts nothing. Load it from your platform's secret storage
and, if the key is user- or device-derived, treat the keystore as part of the
security boundary it is.

### Key loss

A lost key makes existing files and payloads **permanently unreadable** —
there is no recovery path and no back door. Deciding what a lost key means is
an operational choice:

- **File sinks** may rotate out old files and continue with a new key; only
  the records written under the lost key are gone.
- **Room payloads** have the same per-row semantics, but a reconciliation
  export that includes old rows will fail on them — export before retiring a
  key.
- **SQLCipher** (whole database) is all-or-nothing: losing the passphrase
  loses the database.

Keep the previous key available for at least the retention window of the data
it protects.

### Rotation

Pass a provider that returns the *new* key and a `keysForReading` map that
contains every key still needed to read existing data:

```kotlin
val current = AtomicReference(EncryptionKey("2027-01", newBytes))
val cipher = AesGcmCipher(
    keyProvider = EncryptionKeyProvider { current.get() },
    keysForReading = mapOf(
        "2026-10" to oldBytes,
        "2027-01" to newBytes,
    ),
)
```

New records are written under `2027-01`; records written under `2026-10`
continue to decrypt until they age out. Once retention has removed every old
record (or you have re-encrypted them by reading and writing through the
cipher), drop the old entry — a token whose `keyId` is missing from the
keyring fails with `IllegalStateException`, never with a silent fallback.

Re-encryption is a normal read/write loop: decrypt each stored token and
encrypt it again under the current key, then swap the file atomically. The
convenience constructor `AesGcmCipher(key)` pre-fills the keyring with that
single key.

## Tamper detection

GCM authenticates ciphertext and IV. Flipping one bit in a stored token — or
swapping two tokens between records — makes decryption throw
`AEADBadTagException` (a `GeneralSecurityException`) instead of returning
altered data. Malformed tokens (missing prefix, unknown key id, bad base64,
too-short payload) are rejected with `IllegalArgumentException`; a well-formed
token under an unknown key id raises `IllegalStateException`.

Encryption at rest protects against someone reading or editing the storage
medium. It does **not** protect a compromised process, which holds the key
while running. Pair it with the hash chain ([30](30-integrity.md)) when you
need to prove *which* records existed, and use SQLCipher when the queryable
columns themselves are sensitive.

## Reading and rotating with the CLI

`ilogger-cli` understands the same `ilogenc1:` tokens. Every read command
accepts `--decrypt-key`; repeat it for a keyring covering records written
before a rotation:

```bash
ilogger tail audit.jsonl --decrypt-key prod=file:/run/secrets/audit-aes.key
ilogger verify audit.jsonl --decrypt-key old=... --decrypt-key new=... \
  --hmac-key prod=file:/run/secrets/audit.key
ilogger export app.db --decrypt-key prod=file:/run/secrets/audit-aes.key --format csv
```

Replay is also the rotation path: decrypt with the old keyring and write the
same events under the new key. Ids, timestamps and payloads are preserved, so
the rewritten file remains verifiable and queryable:

```bash
ilogger replay audit.jsonl --decrypt-key old=file:/keys/old \
  --to jsonl:rotated.jsonl --encrypt-key new=file:/keys/new --overwrite
```

Key material is never echoed; use `file:` specs so it stays out of shell
history. See [36](36-cli.md) for the key grammar and all commands.

## Limits and cost

| Concern | Answer |
|---|---|
| Algorithms | AES/GCM/NoPadding only; 128-bit tag; 12-byte random IV per record |
| Cipher scope | `TextFileSink` lines, Room payload column |
| Thread safety | `AesGcmCipher` creates a `Cipher` per call and is safe to share |
| Performance | One AES-GCM operation per record (microseconds); negligible next to file fsync |
| Searchability | Encrypted files cannot be grepped; decrypt via the cipher before analyzing |
| Compression | Apply gzip after encryption is impossible per line; the file sink encrypts the already-encoded line, and `Rotation.gzip` compresses the rotated archive (still encrypted payloads, no plaintext leak) |

`TextFileSink` fetches the key per record, so a provider that consults the
keystore on every call adds that latency to every write — cache the
`EncryptionKey` and rotate by swapping the cached reference.

# 23. Diagnostics catalog

Every diagnostic follows the format **"What. \nFix: ..."** — a straight
statement of the problem and an actionable remedy with the exact syntax to
copy.

## Entity / entity id

| Diagnostic text (shape) | Cause | Fix |
|---|---|---|
| `Cannot infer entity ID of 'X'. No provable source: the result is not an id, no entity id property (...), and no single id-typed parameter.` | no provable identity in the signature | `@Log(entityId = "<expr>")`, or pass an entity-typed param, or take a Long id param |
| two id-shaped params with no selection | ambiguous identity | `entityId = "fromId"` explicit |
| `LogMeta: unknown source 'task'. Known sources: result, actor, context.<key>, parameter names (title).` | the source expression references nothing real | fix the path or rename to an existing param |

## Value grammar

| Diagnostic | Cause | Fix |
|---|---|---|
| chained access into a vararg param | processor cannot prove a stable selection | whole-param + extractor with `name` |
| `Extractor + sensitive=MASK/HASH combination` | policy coercion cannot apply inside extractor outputs | apply the policy inside the extractor |
| `sampling < 1.0` on AUDIT level | audit is never sampled | drop the override, or choose a non-audit level |

## Semantics rejections

| Diagnostic | Cause | Fix |
|---|---|---|
| `INLINE` + non-suspend function | dead combination: `recordNow` cannot run inline on the calling thread synchronously | pick ASYNC/BUFFERED, or make the function suspend |
| sensitive-value selection without policy (`DENY`) | contract protects the value | `sensitive = SensitiveHandling.HASH/MASK/OMIT`, or remove the selection |
| `@LogExclude` referenced anywhere | values excluded by design | delete the reference; audit exactly what you meant |

## Warnings (non-fatal)

- `suspiciousName`: risky names (password, token, cookie, ...) even without
  annotations — a warning because it may be a false positive; silence it by
  declaring the policy explicitly at the selection site.
- Duplicate metadata keys (`name` collisions) are warnings.

## Behavior details

- KSP diagnostics surface in the build as `e: [ksp] <text>` (processor
  messages) or as K2 errors otherwise; generated output lives in
  `build/generated/ksp/<source set>/kotlin/`.
- The fatal-diagnostics paths are **automated** in the `:ksp-diagnostics`
  module: six intentionally invalid fixtures (unknown source, unprovable
  entity id, INLINE+non-suspend, audit sampling, sensitive-without-policy,
  vararg chains) run the processor in fixture mode
  (`ilogger.expect-diagnostics=true` — errors become warnings and are written
  to `build/generated/ksp/main/resources/ilogger-diagnostics.txt`), and a
  JUnit test asserts each `// EXPECTED:` marker against the report verbatim.
  `./gradlew :ksp-diagnostics:test` re-verifies the full catalog.

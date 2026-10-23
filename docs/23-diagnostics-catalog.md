# 23. Diagnostics catalog

Every processor diagnostic has a stable code (`ILG001` …), a severity and a
copy-paste fix. The code is public contract: it appears in build output, in
`@Suppress("ILGxxx")` and in the generated schema tooling, so it is never
reused or renumbered. The full table below is asserted against the processor
by `:ilogger-ksp:test` (`DiagnosticCatalogTest`) — a code without a row fails
the build.

**Errors** stop compilation (in normal mode). **Warnings** never fail the
build by themselves; set `ilogger.strict = true` to make warnings fatal in CI.
Messages follow the format **"What. \nFix: ..."** with the exact syntax to
copy.

## Catalog

| Code | Severity | Summary | Fix |
|---|---|---|---|
| `ILG001` | error | @Log applied to a declaration that cannot be wrapped | apply @Log to a function of an interface, an open/abstract class member, or a top-level function |
| `ILG002` | error | @Log member of a final class | mark the class open/abstract, move the operation into an interface, or call ILogger.record |
| `ILG003` | error | class wrapper cannot override the member (abstract, non-open, inner class or private constructor) | instrument a concrete open member, make the class/constructor accessible, or move to an interface |
| `ILG004` | error | generated wrapper would live in the default package | move the declaration into a named package |
| `ILG010` | error | blank or malformed action code | use '<domain>.<event>[.<variant>]', e.g. 'task.created' |
| `ILG011` | error | invalid schemaVersion | use schemaVersion >= 1 |
| `ILG012` | error | invalid @Log/@LogConfig field value | use a member of the annotation's enum or a value in the documented range |
| `ILG013` | error | sampling on an AUDIT event | remove sampling, use a diagnostic level, or configure audit sampling at the application level |
| `ILG014` | error | function signature cannot be instrumented as annotated | make the function suspend for INLINE/LOG cancellation, declare explicit types, or remove the override |
| `ILG015` | error | cannot infer a single entity | add @Log(entity = Task::class) |
| `ILG016` | error | cannot infer a single entity id | add @Log(entityId = "<parameter or result path>") |
| `ILG017` | error | entity/entityId source is unusable | reference a resolvable domain type and a parameter/result id, never the ambient actor |
| `ILG018` | error | actor override references the ambient actor | omit @Log(actor) to inherit the ambient actor, or select a parameter/result value |
| `ILG019` | warning | explicit actor override selects sensitive attributes | verify the container policy is the intended projection, or select non-sensitive attributes |
| `ILG020` | warning | audit operation has no explicit actor with ilogger.require-actor enabled | add @Log(actor = ...), record an ambient actor, or remove ilogger.require-actor |
| `ILG021` | warning | category has no configured sink binding | bind a sink to the category, or add it to ilogger.sink-categories |
| `ILG030` | error | invalid value-expression syntax or source | use result, actor, context.<key>, a parameter name, a property chain or a quoted constant |
| `ILG031` | error | property chain is missing or excluded | correct the chain, or relax @LogExclude for genuinely loggable data |
| `ILG032` | error | sensitive value selected without a policy | choose MASK/HASH/OMIT or select a different value |
| `ILG033` | error | entity id selects a sensitive value | pick a non-sensitive id source |
| `ILG034` | error | change tracking selects a sensitive value | track non-sensitive fields, or record the value through LogMeta with MASK/HASH |
| `ILG035` | warning | selected property name looks sensitive but carries no protection annotation | confirm the selection, or mark the property @LogSensitive/@LogExclude |
| `ILG040` | error | invalid LogMeta declaration | provide a value, a unique name and a supported loggable type, or an extractor/adapter |
| `ILG041` | error | invalid extractor/adapter declaration | use one class that implements the converter interface and is constructible or registered |
| `ILG042` | error | invalid LogChange declaration | provide a unique field name and loggable before/after values |
| `ILG050` | error | @LogDao applied to a non-interface | apply it to the DAO interface (Room @Dao types are interfaces) |
| `ILG051` | error | @LogDao(hilt = true) without Hilt on the classpath | add the Hilt dependencies, or remove hilt = true |
| `ILG052` | error | @LogDao(koin = true) without Koin on the classpath | add koin-core, or remove koin = true |
| `ILG053` | warning | @LogDao interface is not exposed by any @Database accessor | expose it from a database, remove @LogDao, or set enabled = false |
| `ILG054` | error | the same DAO is bound by two databases | keep the DI flag on one database, or bind the DAO manually with db.logged(logger) |
| `ILG060` | error | @LogActions applied to a non-object | declare `object TaskActions { ... }` and list its codes in @LogActions(...) |
| `ILG061` | warning | action code has a single segment | use '<domain>.<event>', e.g. 'task.created' |
| `ILG062` | warning | action code declared or emitted more than once | give distinct operations distinct codes, or suppress ILG062 when the shared contract is intentional |
| `ILG063` | warning | action used but absent from every @LogActions registry | add the code to the registry object's @LogActions(...) arguments |
| `ILG064` | error | same action+version with different payload shapes | bump @Log(schemaVersion = ...) when the shape changes, or align the selections |
| `ILG065` | warning | new schemaVersion with an identical shape | keep a single schemaVersion; bump it only when the payload shape changes |

Ranges group related rules: `00x` targets/shape, `01x` annotation values,
`02x` actor/routing warnings, `03x` value expressions and sensitivity, `04x`
metadata/changes, `05x` DAO opt-in, `06x` action registries and schema
contract.

## Suppressing a diagnostic

Suppress per declaration (or per file) when the report is intentional:

```kotlin
@Suppress("ILG062") // same contract intentionally emitted by two operations
@LogActions("task.created", "task.created")
object TaskActions

@Suppress("ILG") // every diagnostic on this declaration
interface LegacyGateway { … }
```

`@Suppress` is honored on the annotated declaration, its enclosing
declarations and the containing file. `@Suppress("ALL")` suppresses
everything. Module-wide, pass codes to the processor or the Gradle DSL:

```kotlin
ksp { arg("ilogger.suppress", "ILG035,ILG061") }
```

Suppression only hides the report: it never changes generated code or runtime
behavior.

## Warnings added for discoverability

- **Duplicate action declarations or emissions (`ILG062`).** Two operations
  sharing one action+version make events indistinguishable; the schema
  manifest still lists both sources. Suppress when the shared contract is
  intentional (e.g. wrappers of the same logical operation).
- **Undeclared actions (`ILG063`).** Once a module has any `@LogActions`
  registry, codes outside it warn — typos and one-off actions surface at
  compile time.
- **Sensitive actor attributes (`ILG019`).** An explicit `@Log(actor = ...)`
  selecting a `@LogSensitive` value records the container's MASK/HASH
  projection, or falls back to the ambient actor under OMIT. Confirm that is
  what you meant.
- **Missing audit actor (`ILG020`, opt-in).** With
  `ilogger.require-actor = true`, AUDIT operations without an explicit actor
  rely on the ambient actor; deterministic attribution needs
  `@Log(actor = ...)` or `withActor { }`.
- **Unbound category (`ILG021`, opt-in).** With
  `ilogger.sink-categories = "audit,security"`, categories outside the list
  warn, catching routing typos before events vanish silently.
- **Unused `@LogDao` (`ILG053`).** A DAO opted into logging but never exposed
  by a `@Database` accessor produces no wrapper; remove `@LogDao` or expose
  the accessor.
- **Suspicious names (`ILG035`).** Properties that look sensitive by name
  (`password`, `token`, `cookie`, …) but carry no annotation are logged only
  when selected explicitly; the warning asks you to confirm.

## IDE and build integration

- Messages are emitted as `e: [ksp] [ILGnnn] What… Fix: …` so IDEs group them
  by declaration and show the fix inline.
- The Gradle plugin exposes `./gradlew iloggerDiagnostics`, which prints this
  catalog (code, severity, summary, fix) with the plugin's own version, for
  discoverability without leaving the build.
- Every code above is covered by fixtures in `:ksp-diagnostics`,
  `:ksp-diagnostics-warnings` and `:ksp-diagnostics-options`; their JUnit
  suites assert the `// EXPECTED:` markers verbatim, including suppression
  (a suppressed marker must be absent from the report).
- The generated action manifest (`META-INF/ilogger/schema.json`) carries
  `action`, `schemaId`, `entity`, `metadata`, `changes` and `sources` keys so
  CI can diff contracts between releases.

## Disabling without triggering diagnostics

The clean opt-out is `ksp { arg("ilogger.enabled", "false") }` (or
`ilogger { enabled.set(false) }`): the processor becomes a no-op — nothing is
generated, no diagnostics are produced. Runtime disablement stays separate
(`ILogger.disabled` or `enabled = false`).

## Behavior details

- KSP diagnostics surface in the build as `e: [ksp] <text>` (processor
  messages) or as K2 errors otherwise; generated output lives in
  `build/generated/ksp/<source set>/kotlin/`.
- Fixture mode (`ilogger.expect-diagnostics = true`) turns fatal diagnostics
  into warnings and writes every emitted diagnostic to
  `build/generated/ksp/<source set>/resources/ilogger-diagnostics.txt`. The
  fixture modules assert that file; it is not part of the published artifact.
- `ilogger.strict = true` promotes warnings to errors in normal mode (CI
  recommended). Fixture mode ignores strictness so negative tests can run.

# Design: Classfile-based Java API extraction

Status: **Phases 1 & 2 implemented** (sbt/zinc#1714; jozanek/zinc#1, stacked). **Phase 3 is this plan.**
Motivating issues: [#837], [#151], [#1697], sbt/sbt#117.

## What changed since the original plan (Phase 2 landed)

The original plan scoped Phase 2 as "erased-descriptor types only, no `Signature` parser, ~1 PR" and
Phase 3 as "full replacement + `Signature` parser + re-hash". Reality diverged in ways that reshape
Phase 3:

1. **Phase 2 grew well past erased-types-only.** `ClassfileToAPI` (in `zinc-apiinfo`) now
   conservatively *folds* the public-shape signals an erased descriptor can't capture — `static final`
   constant values (Singleton encoding), the raw generic `Signature` string, checked exceptions
   (`throws`, resolved names), and declared annotation **types** — into the recorded API, fixes
   main-class detection, and is hardened so a malformed classfile never fails the compile. So Phase 2
   already detects *changes* to almost every public-shape delta. **Consequence: Phase 3's value moves
   from "catch changes we miss" (largely done) to "eliminate reflection — the #151/#1697 root cause —
   and replace the conservative folds with proper fidelity."**
2. **Plumbing differs from the sketch.** Rather than changing `readAPI` to take a `ClassFile`, Phase 2
   added a *parallel* `readClassfileAPI` callback on `JavaAnalyze` (no-op default; wired to
   `ClassfileToAPI` in `AnalyzingJavaCompiler`) used only for un-loadable classes. Phase 3 generalizes
   this to route **all** Java classes through the classfile path and retire `readAPI(Seq[Class])`.
3. **Reflection is more entangled in `JavaAnalyze` than the sketch implied.** Beyond API extraction it
   still uses class loading for: source-name mapping + local-vs-non-local partition
   (`getCanonicalName`/`getEnclosingClass`); **inherited members** (`getMethods`/`getFields` return
   inherited — so eliminating reflection means resolving & parsing **parent classfiles** off the
   classpath, a capability that does not exist yet); and **binary-dependency origin** resolution
   (`loader.getResource`, separable from API). Phase 3 must address each — see §5.
4. **Phase ordering resolved** (Phase 2 shipped first, as recommended). The hash-strategy question is
   still open; the recommendation is unchanged (accept a one-time re-hash).

## 1. Problem

Zinc extracts the API of javac-compiled classes by **reflectively loading them**.
`ClassToAPI.process` takes `Seq[Class[?]]`; `JavaAnalyze` loads each compiled class with
`Class.forName(name, false, loader)` (a classloader over the output dir + classpath) and hands the
`Class` objects to `ClassToAPI`, which walks them with `java.lang.reflect` (`getDeclaredMethods`,
`getGenericSuperclass`, inner-class loading, `getAnnotations`, …).

That class-loading step is the root cause of a recurring family of bugs:

| Issue | Symptom | Mechanism |
|-------|---------|-----------|
| [#151] | static initializers run during analysis (side effects, `ExceptionInInitializerError`) | reflection forces class init/link |
| [#1697] | `NPE` in `loadInnerClass` on JDK 21+ (`Thread.Builder`) | reflective inner-class walk |
| sbt/sbt#117 | `NoClassDefFoundError` for optional deps (Spring/JBoss VFS) | superclass/ref type not on classpath |
| [#837] | `IllegalAccessError` for `--add-exports` types | superclass in non-exported module |

The maintainer's conclusion on #151 was explicit: *"we need to avoid using any APIs that cause
classes to load."* The code agrees — `ClassToAPI` carries a standing TODO: *"over time, ClassToAPI
should switch the majority of access to the classfile parser."*

**Current state (after the #837 fix):** crashes are caught; an un-loadable class still gets its
product, member-ref deps, and inheritance edges recorded *from the classfile*, but **no
`api.ClassLike`** — so name-hashing invalidation can miss dependents when that class's public shape
changes. This doc specs the end state that removes the loading step entirely.

## 2. Goals / non-goals

**Goals**
- Build `xsbti.api.ClassLike` for each javac-produced class directly from its `.class` bytes.
- No class loading during Java analysis (no classloader, no static initializers, immune to
  module/classpath resolution failures).
- Output complete and stable enough for name-hashing; ideally hash-comparable to today's output.
- Resolve the bug family at the root, not case-by-case.

**Non-goals**
- Scala API extraction (done in the compiler bridge, unaffected).
- Changes to the incremental algorithm or the persisted analysis schema.

## 3. What already exists vs. what's missing

`ClassToAPI` is **already partly classfile-based**: it parses the classfile for constant values
(`cf.constantValue`, for inlined `static final` fields) and enumerates inner classes from the
`InnerClasses` attribute (`innerClassesFromClassfile`). So the parser is already in the hot path.

The classfile parser (`internal/zinc-classfile/.../ClassFile.scala`) exposes:
`className`, `superClassName`, `interfaceNames`, `accessFlags`, `fields`/`methods`
(`FieldOrMethodInfo(accessFlags, name, descriptor, attributes)`), `innerClasses`
(`InnerClassInfo(accessFlags, innerName, innerClassName, outerClassName)`), `constantValue`,
`sourceFile`, and `types` (constant-pool + annotation type references).

**Gaps to fill before a faithful API can be built:**

1. **`Signature` attribute parsing** (JVMS §4.7.9) — the main effort. Carries generic type
   information (type parameters, bounds, parameterized types, wildcards) that reflection exposes via
   `getGeneric*`. The raw attribute bytes are captured (`AttributeInfo.isSignature`) but not parsed.
   Need a recursive-descent parser for ClassSignature / MethodSignature / FieldTypeSignature → map to
   `xsbti.api` types, mirroring `ClassToAPI.reference`/`typeParameter` but from signature strings.
2. **Structured descriptor → (params, return, exceptions)**: descriptor parsing exists
   (`descriptorToTypes`, used for deps) but isn't structured into method shapes. `Exceptions`
   attribute (checked exceptions) also needed.
3. **Annotation values**: parser resolves annotation *type* references; `ClassToAPI` records
   `annotation.toString`. Need annotation argument extraction (or an agreed equivalent) from the
   `RuntimeVisible/InvisibleAnnotations` attributes (partial parsing already exists in
   `annotationsReferences`).
4. **Enum children**: `ClassToAPI.childrenOfSealedClass` uses `Class.isEnum`; from the classfile this
   is `ACC_ENUM` + the enum-constant static fields.
5. **Member flags**: `varargs`/`bridge`/`synthetic`/`abstract`/`final` from method/field
   `accessFlags` (mostly available; map the remaining flags).

Access/modifiers, parents, inner-class structure, and constants are already derivable.

## 4. Design

**Placement.** A new `ClassfileToAPI` (or an alternate entry point on `ClassToAPI`) in
`zinc-apiinfo`, which already depends on both `compiler-interface` (`xsbti.api`) and `zinc-classfile`
(the parser). The Signature parser belongs in `zinc-classfile` alongside the existing parser.

**Plumbing.** Phase 2 added a parallel `readClassfileAPI` callback on `JavaAnalyze` (no-op default;
wired to `ClassfileToAPI` in `AnalyzingJavaCompiler`) used only for un-loadable classes. Phase 3
routes **all** classes through it and retires `readAPI(Seq[Class])`, so `JavaAnalyze` no longer
`Class.forName`s the compiled classes — and must also drop the reflection it uses for source-name
mapping, the local/non-local partition, and inherited members (see §5). (All `sbt.internal.*` /
hermetic, so no MiMa impact.)

**Construction.** Mirror `ClassToAPI.toDefinitions0`/`structure`, sourcing each field from the
classfile: name via the `InnerClasses` canonical-name walk (already prototyped as
`JavaAnalyze.classFileSourceName`), access/mods from flags, parents from super/interfaces (generic
via `Signature`), members from fields/methods (types via descriptor, generics via `Signature`),
static/instance split via `ACC_STATIC`, inner classes by recursion, enum children via `ACC_ENUM`.

## 5. Hash compatibility & rollout

Producing APIs that hash **identically** to today's reflection output is hard (member ordering,
synthetic/bridge methods, generic-signature formatting, annotation representation). Two stances:

- **Hash parity** — invest to match exactly so the migration is invisible. High effort, fragile.
- **Accept a one-time re-hash** (recommended) — all Java APIs re-hash once on the Zinc version that
  ships this, triggering one full recompile of Java sources. Zinc already does this for
  format/version bumps. Far simpler; the hard requirement is only **self-consistency**
  (deterministic, stable across runs) for name-hashing to work.

**Phased rollout (each phase shippable & testable):**

- **Phase 1 — shipped** (sbt/zinc#1714). Crash fix; un-loadable classes get products + member-ref +
  inheritance deps from the classfile.
- **Phase 2 — shipped** (jozanek/zinc#1, stacked). `ClassfileToAPI` records an `api.ClassLike` for
  un-loadable classes, conservatively folding constants / raw `Signature` / `throws` / annotation
  types (see "What changed"). Does **not** touch loadable classes, so #151/#1697 remain.
- **Phase 3 — this plan: eliminate reflection from Java analysis** (resolving #151 and #1697 by
  construction, and making #117/#837 fully faithful) by making `ClassfileToAPI` the single Java API
  path and `JavaAnalyze` classfile-only.

### Phase 3 workstreams

- **A. `Signature` parser** (`zinc-classfile`) — recursive-descent JVMS 4.7.9
  Class/Method/FieldType signatures → `xsbti.api` types (`Parameterized`, `ParameterRef`,
  `TypeParameter`, bounds, wildcards), mirroring `ClassToAPI.reference`/`typeParameter`. Replaces the
  raw-`Signature` fold with real generic types. *Primary technical risk.*
- **B. Remaining member fidelity** in `ClassfileToAPI` — annotation **element values** (proper
  `api.Annotation` args), enum children (`ACC_ENUM` + enum-constant fields), and type parameters
  (from the parsed signature). Replaces the Phase-2 folds.
- **C. Inherited members** — resolve parent classfiles by name across output + classpath (a new
  classfile index, since today only output classfiles are parsed), parse them, and include inherited
  public members like `ClassToAPI.merge`. *Second-biggest piece* — or descope (open question 4).
- **D. `JavaAnalyze` de-reflection** — replace `load` / `loadEnclosingClass` / the
  `getCanonicalName` partition with classfile-derived names (reuse `classFileSourceName`); route all
  classes through `readClassfileAPI`; retire `readAPI(Seq[Class])`. Decide binary-dependency origin:
  keep a classpath for the `loader.getResource` lookup, or switch to a file search.
- **E. Rollout** — accept a one-time re-hash of all Java APIs (a single full Java recompile on the
  shipping version); optionally keep reflection behind a flag for one release as a safety valve.

### Sequencing (each independently testable; differential-tested vs the reflection output)

1. `Signature` parser + unit tests (standalone, no wiring).
2. Fold A+B into `ClassfileToAPI` (still un-loadable-only); differential-test vs `ClassToAPI` on a
   classfile corpus.
3. Inherited members (C); differential-test.
4. Switch loadable classes to `ClassfileToAPI` **behind a flag** (D); differential-test whole-class
   `HashAPI` vs reflection across a corpus; drive to acceptable parity.
5. Flip the default; remove reflection (or keep the flag one release).

## 6. Testing

- **Differential testing**: over a corpus of Java classes, compare reflection-derived vs
  classfile-derived API via `HashAPI`; drive to equivalence or catalog intentional differences.
- Extend `ClassToAPISpecification` / `AnalyzeSpecification` (the #837 tests already exercise the
  un-loadable path and the harness `compileJava`/`analyze` helpers).
- **Scripted** incremental scenarios: change a Java class's public method → assert dependents
  recompile, including for the un-loadable case.
- Targeted regressions: #151 (no static init — a class with a side-effecting `static {}`),
  #1697 (extends `Thread` on JDK 21+), sbt/sbt#117, #837.

## 7. Risks

- `Signature` parser correctness (primary technical risk; well-specified grammar, but fiddly).
- Behavioral parity with reflection quirks (synthetic/bridge methods, enum modeling, annotation
  representation, constant inlining).
- One-time re-invalidation on rollout (mitigated by documenting; or by hash-parity investment).
- Long-lived branch / scope creep — mitigated by the phased plan.

## 8. Open questions for maintainers

1. Hash parity vs. accept a one-time re-hash? (Recommendation: accept the re-hash.) — **still open.**
2. Remove reflection entirely in Phase 3, or keep it behind a flag for one release? — **still open.**
3. ~~Land Phase 2 first, or go straight to Phase 3?~~ — **resolved: Phase 2 shipped first.**
4. **Inherited members (new):** parse parent classfiles for full fidelity (needs a classpath
   classfile index — workstream C), or rely on the inheritance dependency edge alone (a parent change
   already invalidates the subclass via the dep graph, so the subclass's *own* recorded API may not
   need to embed inherited members)? This decision materially changes Phase 3's size.

[#837]: https://github.com/sbt/zinc/issues/837
[#151]: https://github.com/sbt/zinc/issues/151
[#1697]: https://github.com/sbt/zinc/issues/1697

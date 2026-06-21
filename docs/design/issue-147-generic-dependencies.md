# Issue #147 — Java generic-position dependencies

[sbt/zinc#147]: classes used only in a generic position (e.g. `Foo` in `List<Foo>`, or a bound in
`<T extends Foo>`) are erased out of the javac-produced classfile's descriptors and constant pool, so
zinc's post-hoc **javac analysis** (`JavaAnalyze` → `ClassFile.types`) does not record the dependency.
The erased name survives only in the JVMS 4.7.9 `Signature` attribute.

## What this change does

Scans the `Signature` attribute on the class itself and on each field and method, and folds the
referenced class names into `ClassFile.types` (`Parser.signatureClassTypes`). Those names flow through
the existing `DependencyByMemberRef` path in `JavaAnalyze` with no downstream change. Best-effort: a
malformed signature only warns, mirroring the annotation parser.

## Scope and known limitations

This fixes the **declaration-level** cases — field types, method parameter/return types, parameterized
supertypes, and type-parameter bounds — whose `Signature` attributes are always emitted by javac
(independent of `-g`).

It does **not** fix the issue's headline example, where `Foo` is used only in a **method-local**
generic type:

```java
List<Foo> xs = ...;   // Foo used nowhere else
```

Empirically (javac 11):
- **default `javac` (no `-g`)**: `Foo` is erased *entirely* — it is not in any `Signature`, not in
  `LocalVariableTypeTable`, nowhere. No classfile reader can recover it.
- **`javac -g`**: `Foo` lands in `LocalVariableTypeTable` (a `Code` attribute), which this scanner
  intentionally does not read (debug-only, not part of the public member-ref surface).

So the method-local / fully-erased case is unreachable for any classfile-based approach; it requires
collecting references from javac's **attributed AST** during compilation (the approach discussed in
[sbt/zinc#145]).

## Why there is no scripted/end-to-end test

#147 only manifests on the **pure-javac path** (`JavaAnalyze` with no Scala compiler involved) — i.e.
build tools that drive zinc's Java analysis directly (Bazel, Gradle, Mill, Maven).

Under sbt 2.x the scripted harness compiles every project through scalac with `-Ypickle-java`. scalac
works on the **typed AST**, so its used-names analysis records `User`'s use of `Box` from `List<Box>`
*regardless of this fix* — masking the gap. (Verified from a scripted run's debug log:
`External API changes: NamesChange(Box,...)` → `external source: Set(User)`; a scripted test passed
with the fix disabled.) Consequently a scripted test cannot isolate #147.

The authoritative regression tests are therefore direct `JavaAnalyze` unit tests
(`AnalyzeSpecification`), which exercise the javac path without scalac and fail when the fix is
reverted:
- co-compiled source → **internal** `classDependency` ("… used only as generic type arguments",
  "… on generic type-parameter bounds");
- separately-compiled, classpath-resolved class → **external** `binaryDependency` ("extract binary
  dependencies on external classes used only as generic type arguments"). This covers the classloader
  origin lookup that multi-project / library clients (Gradle, Bazel, Maven) actually rely on.

`ParserSpecification` covers the signature-grammar edge cases (nesting, bounds, wildcards, arrays,
inner classes, type-variable exclusion) directly.

## Follow-ups (deferred by decision)

- [ ] **Unify the signature parser** — `feat/classfile-java-api-phase3` introduces a structured
  recursive-descent `SignatureParser` (+`SignatureModel`) for classfile API extraction.
  `Parser.signatureClassTypes` here is a deliberately lightweight scanner (it needs only the *set* of
  referenced binary names, not a type model). **Decision: revisit after phase3 merges** — replace this
  scanner with a binary-name visitor over the shared `SignatureParser` so the two implementations
  cannot diverge.
- [ ] **Capture fully-erased references** (method-local generics, the #147 headline) via javac
  attributed-AST collection. **Decision: do not attempt until [sbt/zinc#145] is fixed.** Until then
  #147 should stay open; this change is a partial (declaration-level) fix.

[sbt/zinc#147]: https://github.com/sbt/zinc/issues/147
[sbt/zinc#145]: https://github.com/sbt/zinc/issues/145

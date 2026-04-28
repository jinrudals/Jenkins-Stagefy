# Research: OOP Refactoring of Stagefy

**Branch**: `003-oop-refactoring` | **Date**: 2026-04-28

## R1: Groovy CPS Serialization Strategy for Jenkins Shared Libraries

**Decision**: Hybrid approach — core Stage/Step classes implement `Serializable`; utility/helper classes (factories, resolvers, builders) are stateless with `@NonCPS` annotation only.

**Rationale**: Jenkins CPS transforms pipeline code into a serializable continuation. Any object stored in a closure or field that crosses a CPS-transformed method boundary must be `Serializable`. Stage and Step objects are passed through closures (e.g., `stage("name") { stageObj.run() }`) and must survive serialization. Utility classes that perform pure computation (string substitution, name building, list expansion) never cross CPS boundaries and can be `@NonCPS`-only, avoiding serialization complexity.

**Alternatives considered**:
- All classes `Serializable` with `@NonCPS` helpers: simpler but forces unnecessary serialization overhead on stateless utilities
- Minimize class state / pass-through parameters: avoids serialization but loses OOP benefits (encapsulation, readability)

**Key constraints**:
- `JsonSlurper` returns `LazyMap` (not serializable) — must convert to `LinkedHashMap` before returning from `@NonCPS` methods
- Closures captured in `parallel()` map must not reference non-serializable transient state
- `@NonCPS` methods cannot call CPS-transformed methods (no `sh()`, `stage()`, `readYaml()` inside `@NonCPS`)

## R2: Recursive Template Variable Substitution

**Decision**: Recursive substitution until stable, with a depth limit of 5.

**Rationale**: Users may compose templates where one argument's value contains references to other arguments (e.g., `{DB_URL}` resolves to `jdbc:{DB_HOST}:5432`). Single-pass substitution would leave inner tokens unresolved. Recursive resolution enables full composability. Depth limit of 5 prevents infinite loops from circular references like `{A}→{B}→{A}`.

**Alternatives considered**:
- Single-pass: simpler but leaves nested references unresolved — breaks user expectations for composable templates
- Two-pass: arbitrary limit that doesn't generalize

**Implementation approach**:
- `TemplateResolver.resolveStepsWithBindings()` calls `substituteBindings()` in a loop
- After each pass, check if any `{...}` tokens remain that match known binding keys
- If no changes occurred or depth limit reached, stop
- If depth limit reached with unresolved tokens that match binding keys, throw error indicating circular substitution

## R3: Factory Pattern for Stage/Step Creation

**Decision**: Use simple factory classes (`StageFactory`, `StepFactory`) with explicit type dispatch based on YAML keys.

**Rationale**: The current monolithic `run()` method uses `if/else` chains to dispatch based on YAML structure (`stages`, `parallels`, `steps`). Extracting this into factory classes preserves the same logic but makes it testable and extensible. A registry-based approach (map of key→class) was considered but adds complexity without clear benefit given the small, stable set of types.

**Alternatives considered**:
- Registry pattern with dynamic class loading: over-engineered for ~7 step types and 3 stage types
- Visitor pattern: adds indirection without benefit since dispatch is based on data keys, not object types

**StageFactory dispatch logic**:
- `data.stages != null` → `SequentialStage`
- `data.parallels != null` → `ParallelStage`
- `data.steps != null` → `StepsStage`
- `data.template != null` → error (templates cannot be executed directly)
- else → error with unknown directive message listing valid keys

**StepFactory dispatch logic**:
- `step.sh` → `ShStep`
- `step.script` → `ScriptStep`
- `step.setEnvFromFile` → `SetEnvFromFileStep`
- `step.evaluate` → `EvaluateStep`
- `step.use` → `UseStageStep`
- `step.template` → `TemplateStep`
- `step.iterated` → `IteratedStep`
- else → error with unknown directive message listing valid keys

## R4: Unknown YAML Directive Handling

**Decision**: Fail-fast with clear error message listing the unrecognized directive and valid options.

**Rationale**: Unknown directives are almost always typos or configuration errors. Silently ignoring them leads to hard-to-debug pipeline failures downstream. Fail-fast with actionable error messages (showing what was found and what was expected) aligns with User Story 5 (improved error diagnostics).

**Implementation**: Both `StageFactory` and `StepFactory` include a final `else` branch that calls `jenkins.error()` with the unknown key and a list of recognized keys.

## R5: DEBUG_LEVEL Environment Variable for Execution Tracing

**Decision**: Use `DEBUG_LEVEL` environment variable to control logging verbosity.

**Rationale**: Pipeline debugging is difficult without visibility into which stages/steps are executing. A simple environment variable avoids adding Jenkins plugin dependencies or complex logging frameworks. Levels: 0 (silent/default), 1 (stage entry/exit), 2 (step-level detail including resolved parameters).

**Implementation approach**:
- Read `env.DEBUG_LEVEL` once at `run()` entry point, default to `0`
- Stage `run()` methods log entry/exit at level ≥ 1
- Step `run()` methods log execution detail at level ≥ 2
- Logging via `println` (Jenkins console output) — no external dependencies

## R6: File Organization Strategy for Single-File Groovy Shared Library

**Decision**: Keep all classes in `vars/stagefy.groovy` as inner/nested classes within the existing file structure.

**Rationale**: Jenkins Shared Libraries have a specific convention: `vars/*.groovy` files define global pipeline steps. While `src/` directory supports package-structured classes, moving to `src/` would change the import/loading mechanism and potentially break existing pipelines. The refactoring goal is internal restructuring, not changing the library's external interface. All classes remain in `vars/stagefy.groovy` but are clearly separated with class-level documentation.

**Alternatives considered**:
- `src/` package structure: cleaner separation but changes how Jenkins loads the library; risks breaking existing `@Library` imports
- Multiple `vars/` files: each file becomes a separate global step; doesn't support class hierarchy sharing

**Trade-off**: Single file is larger but maintains 100% backward compatibility with existing Jenkins pipeline configurations.

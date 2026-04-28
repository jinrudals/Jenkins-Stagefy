# Research: Stage Templates with Iteration

## Decision: Variable Placeholder Syntax

**Decision**: Use `{VAR_NAME}` (single braces, no `$`) for template variable substitution.

**Rationale**: The existing codebase already uses `${env.VAR_NAME}` for runtime env substitution (regex pattern `/\$\{\s*env\.[a-z|A-Z|_|\.]*\s*\}*/` in `steps_run()`). Using `{VAR_NAME}` is visually distinct and avoids any ambiguity. Template substitution runs before env substitution, so a step value of `compile {TARGET} ${env.FLAGS}` resolves first to `compile arm64 ${env.FLAGS}`, then env substitution runs normally.

**Alternatives considered**:
- `${VAR_NAME}` — conflicts visually with env syntax; confusing for authors
- `{{VAR_NAME}}` — double-braces (Jinja/Mustache style); heavier syntax, harder to read

---

## Decision: Template Declaration Structure

**Decision**: A template stage has a `template:` key (containing `arguments:` list) alongside a `steps:` key at the same level.

```yaml
compile_target:
  template:
    arguments: [TARGET, MODE]
  steps:
    - sh: compile --target {TARGET} --mode {MODE}
```

**Rationale**: This reuses the existing top-level stage structure. The `template:` key acts as a marker that (a) declares the stage is a template and (b) declares its required argument names. The `steps:` key follows the existing convention. The `run()` dispatcher simply checks for `template != null` first and rejects direct execution.

**Alternatives considered**:
- Dedicated `templates:` top-level section — breaks the flat stage registry pattern; requires a separate lookup path
- `type: template` marker — more verbose, less self-documenting than `template: {arguments: [...]}`

---

## Decision: Generated Stage Name for Iterated Stages

**Decision**:
- Scalar `over:` → `{templateName}_{value}`, e.g., `compile_target_arm64`
- Map `over:` → use reserved `_name:` key in the map entry if present, otherwise fall back to `{templateName}_{index}` (0-based)

**Rationale**: Stage names must be unique and visible in Jenkins output. Scalar names incorporate the value directly, which is always a short string. Map entries often contain multiple values; a required `_name:` key gives authors control without imposing a complex naming convention. Index fallback ensures uniqueness at the cost of readability.

**Alternatives considered**:
- Always concatenate all map values — unpredictable length; order-dependent
- Hash of bindings — unique but opaque in Jenkins UI

---

## Decision: `over: env.VAR_NAME` Resolution

**Decision**: Resolve at runtime by calling `this.script.env[varName]`, then auto-detect the format:

1. **Try JSON parse first** (using `groovy.json.JsonSlurper`):
   - Valid JSON array of maps → treat as map list (same semantics as inline `over:` map list); multi-argument templates supported
   - Valid JSON array of strings/primitives → treat as scalar list; single-argument templates only
2. **JSON parse fails → comma-split fallback**: split on `,`, strip whitespace per item → scalar list; backward-compatible with existing conventions

**Rationale**: Jenkins environment variables are always strings, but a string can encode richer structures. Auto-detecting JSON first means a pipeline author can pass `MATRIX=[{"TARGET":"arm64","MODE":"debug"},{"TARGET":"x86_64","MODE":"release"}]` as a Jenkins parameter and use a multi-argument template—without any special new syntax. The comma-split fallback preserves the conventional `PLATFORMS=arm64,x86_64` pattern so nothing breaks.

The `groovy.json.JsonSlurper` class is already imported in the library (`import groovy.json.JsonSlurper` exists in `stagefy.groovy`), so no new dependency is needed.

**Resulting behavior**:
| Env var value | Parsed as | Template arity |
|---------------|-----------|----------------|
| `arm64,x86_64` | `["arm64", "x86_64"]` (scalar list) | single-arg only |
| `["arm64","x86_64"]` | `["arm64", "x86_64"]` (scalar list via JSON) | single-arg only |
| `[{"T":"arm64","M":"debug"},{"T":"x86_64","M":"release"}]` | map list via JSON | multi-arg |
| empty / unset | empty list | zero iterations, no error |

**Alternatives considered**:
- Comma-split only — simple but blocks multi-argument env iteration entirely
- Multiple `over:` keys (one per argument, zipped) — complex new syntax, awkward authoring
- Require explicit `format: json` flag in the `iterated:` block — unnecessary ceremony given reliable auto-detection

---

## Decision: CPS Compatibility Strategy

**Decision**: Mark helper methods that build collections (`@NonCPS`) where they do not call Jenkins DSL steps. The variable substitution logic, template validation, and iteration expansion are all pure collection/string operations and should be `@NonCPS`. The actual stage execution closures remain CPS-transformed.

**Rationale**: Jenkins' CPS transformation can cause `NotSerializableException` for certain Groovy collection operations (e.g., `.collect{}`, `.findAll{}` with closures). Marking pure helper methods `@NonCPS` avoids this. The stage-running closures (`{ stage(name){ ... } }`) must remain CPS-transformed to be pauseable by Jenkins.

**Alternatives considered**:
- Using only `for` loops instead of functional collection methods — avoids `@NonCPS` need but is more verbose
- Making everything `@NonCPS` — cannot call Jenkins DSL (`sh`, `stage`, `parallel`) from `@NonCPS` methods

---

## Decision: Template Validation Timing

**Decision**: Validate template existence and argument completeness **eagerly** at the start of each `parallels_run()` / `stages_run()` / `steps_run()` call, before any stage executes.

**Rationale**: The spec requires errors to be caught before any stage executes (SC-004). Groovy's lazy closure execution would otherwise allow a missing template or argument to surface only when that specific stage runs. Eager validation reads template data upfront and throws errors immediately.

**Alternatives considered**:
- Load-time global validation (scan entire YAML on first load) — not feasible without a full YAML graph traversal; over-engineering for the current flat-file model
- Lazy (per-stage) validation — simpler but violates SC-004

---

## Decision: Template Used with `use` Directive

**Decision**: Template stages are accessible to the existing `use` directive. If a user writes `use: compile_target from Jenkins.yml`, the `use` handler calls `construct_stage()` which calls `run()`, which detects `template != null` and errors. This is the expected behavior per the spec.

**Rationale**: Templates are not regular stages and must not be executed directly. The error message makes the constraint clear.

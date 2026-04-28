# Implementation Plan: Stage Templates with Iteration

**Branch**: `002-parallel-stage-templates` | **Date**: 2026-04-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-parallel-stage-templates/spec.md`

## Summary

Extend Jenkins.yml to support reusable template stages—steps-only definitions with declared arguments. Templates can be instantiated directly (one stage or inlined steps per reference) or via `iterated:` blocks (one stage or step block per collection item) inside `parallels`, `stages`, and `steps` contexts. Variable placeholders use `{ARG_NAME}` syntax and are substituted at instantiation time.

## Technical Context

**Language/Version**: Groovy (Jenkins Shared Library, Jenkins Pipeline DSL)
**Primary Dependencies**: Jenkins Pipeline: Utility Steps Plugin (`readYaml`), `org.jenkinsci.plugins.pipeline.modeldefinition.Utils`
**Storage**: YAML files (Jenkins.yml) — read via `readYaml` at pipeline runtime
**Testing**: Jenkinsfile-based integration tests + YAML fixture files (pattern established by `001-steps-use-directive`)
**Target Platform**: Jenkins CI/CD server
**Project Type**: Jenkins Shared Library (`vars/stagefy.groovy`)
**Performance Goals**: N/A — orchestration tool; no throughput or latency requirements
**Constraints**: Jenkins CPS (Continuation Passing Style) transformation; `@NonCPS` required for pure collection/string helpers; closures used for `parallel` map must be CPS-compatible
**Scale/Scope**: Single file (`vars/stagefy.groovy`); additive changes only

## Constitution Check

*Constitution file is unpopulated (template only). No constitutional gates to evaluate.*

Post-design check: implementation is additive—no existing interfaces broken, no new dependencies introduced, backward-compatible with all current Jenkins.yml files.

## Project Structure

### Documentation (this feature)

```text
specs/002-parallel-stage-templates/
├── plan.md              # This file
├── research.md          # Phase 0: key design decisions
├── data-model.md        # Phase 1: entity definitions and YAML runtime types
├── quickstart.md        # Phase 1: usage guide
├── contracts/
│   └── jenkins-yml-schema.md   # Phase 1: extended YAML schema
└── tasks.md             # Phase 2 output (/speckit.tasks — not yet created)
```

### Source Code

```text
vars/
└── stagefy.groovy           # All changes go here (single library file)

examples/
├── Jenkins.yml              # Existing examples (unchanged)
├── test_templates_direct.yaml       # New: direct template reference fixtures
├── test_templates_iterated_scalar.yaml  # New: scalar iterated fixtures
├── test_templates_iterated_map.yaml     # New: map iterated fixtures
├── test_templates_iterated_env.yaml     # New: env var iterated fixtures
├── test_templates_steps_inline.yaml     # New: steps-context inline fixtures
├── Jenkinsfile.test_templates           # New: Jenkinsfile test runner
└── ... (existing files unchanged)
```

## Implementation Design

### Changes to `stagefy.groovy`

#### New helper: `@NonCPS substituteBindings(String text, Map bindings)`
Replaces all `{ARG_NAME}` occurrences in `text` with corresponding values from `bindings`. Pure string operation; `@NonCPS` safe.

#### New helper: `@NonCPS resolveStepsWithBindings(List steps, Map bindings)`
Returns a new list of step maps with all string values processed through `substituteBindings`. Does not mutate the original.

#### New helper: `@NonCPS validateTemplateData(String templateName, Map templateData)`
Checks:
1. `templateData != null` — template exists
2. `templateData.template != null` — has template marker
3. `templateData.template.arguments` is a non-empty list
4. `templateData.steps != null` — has steps
5. `templateData.stages == null && templateData.parallels == null` — no structural elements

#### New helper: `@NonCPS validateBindings(String templateName, List declaredArgs, Map bindings)`
Checks all `declaredArgs` are present as keys in `bindings`. Throws descriptive error on missing argument.

#### New helper: `@NonCPS expandIteratedOver(iteratedData)`
Resolves the `over:` field into a unified list (of strings or maps):
- Inline list of strings → return as-is (scalar list)
- Inline list of maps → return as-is (map list)
- String starting with `"env."` → extract var name, call `this.script.env[varName]`:
  - Null or empty → return empty list (not an error)
  - Non-null → try `JsonSlurper.parseText()`:
    - JSON array of maps → return as map list (multi-arg supported)
    - JSON array of strings/primitives → return as scalar list
    - JSON parse exception → fall back to comma-split, strip whitespace → scalar list
- All `JsonSlurper` operations must be `@NonCPS`-safe (use `@NonCPS` on this helper; note `JsonSlurper` returns non-serializable objects, so results must be converted to plain `ArrayList`/`LinkedHashMap` before returning)

#### New helper: `@NonCPS buildStageNameForScalar(String templateName, String value)`
Returns `"${templateName}_${value}"`.

#### New helper: `@NonCPS buildStageNameForMap(String templateName, Map entry, int index)`
Returns `"${templateName}_${entry._name}"` if `_name` present, else `"${templateName}_${index}"`.

#### Modified: `parallels_run()`

Current: iterates over list of strings.
Extended: for each entry in the `parallels` list:
- `instanceof String` → existing behavior (unchanged)
- `instanceof Map && entry.containsKey('template')` → validate template, build one stage closure
- `instanceof Map && entry.containsKey('iterated')` → validate template, expand collection, build N stage closures

All closures are collected into the `data` map and passed to `this.script.parallel(data)`.

Duplicate stage name detection: after collecting all keys for the parallel map, check for duplicates and error before calling `parallel()`.

#### Modified: `stages_run()`

Same entry-type handling as `parallels_run()`. Template references and iterated blocks expand into additional `Stagefy`-like closures inserted into the `inside` list. Each runs sequentially via `this.script.stage(name){ ... }`.

#### Modified: `steps_run()`

Current: handles `sh`, `script`, `setEnvFromFile`, `evaluate`, `use`.
Extended: for each step entry:
- `entry.containsKey('template')` → validate template, resolve bindings, inline steps (as if the resolved steps were in the original list)
- `entry.containsKey('iterated')` → expand collection, inline N copies of resolved steps sequentially

#### Modified: `run()`

Add guard before existing dispatch logic:
```groovy
if (temp.template != null) {
  this.script.error("Template stage '${this.stagename}' cannot be executed directly")
}
```

### Variable Substitution

Placeholder syntax: `{ARG_NAME}` (single braces).

Substitution order in `steps_run()`:
1. Template argument substitution (`{ARG_NAME}` → bound value)
2. Existing env substitution (`${env.VAR_NAME}` → `env[VAR_NAME]`)

This allows a template step value like `run --target {TARGET} ${env.FLAGS}` to work correctly.

### env.VAR_NAME Resolution

The `over: env.PLATFORMS` string is detected in `expandIteratedOver()` by `startsWith("env.")`. The variable name is extracted by splitting on `"env."`. Resolution calls `this.script.env[varName]` at runtime. Since Jenkins env vars are always strings, the result is split on `,` and each item is stripped of whitespace.

Empty string or null resolves to an empty list → zero iterations → no error.

## Complexity Tracking

No constitution violations. Changes are additive; no new dependencies; no architectural layers introduced.

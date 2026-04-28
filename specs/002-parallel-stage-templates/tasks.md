# Tasks: Stage Templates with Iteration

**Input**: Design documents from `/specs/002-parallel-stage-templates/`
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓, quickstart.md ✓

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel with other tasks in the same phase (different files, no cross-task dependencies)
- **[Story]**: Which user story this task belongs to
- All implementation tasks target `vars/stagefy.groovy` unless otherwise noted
- All test fixture tasks target new files under `examples/`

---

## Phase 1: Setup

**Purpose**: Create the test runner scaffold before any implementation begins.

- [x] T001 Create test Jenkinsfile skeleton `examples/Jenkinsfile.test_templates` — `@Library('stagefy')` import, `pipeline { agent any; stages { } }` shell with placeholder commented sections for each user story

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core helper methods and the `run()` guard that ALL user stories depend on. No user story work begins until this phase is complete.

**⚠️ CRITICAL**: T002–T006 all modify `vars/stagefy.groovy`. Complete them in order.

- [x] T002 Add `@NonCPS substituteBindings(String text, Map bindings)` method to `vars/stagefy.groovy` — iterates over `bindings` entries and replaces every `{KEY}` occurrence in `text` with the corresponding value; returns the substituted string; no-op if `text` is null
- [x] T003 Add `@NonCPS resolveStepsWithBindings(List steps, Map bindings)` method to `vars/stagefy.groovy` — returns a new list where every string value in each step map has been passed through `substituteBindings(value, bindings)`; handles sh/script/use/evaluate/setEnvFromFile key values; preserves non-string values as-is
- [x] T004 Add `@NonCPS validateTemplateData(String templateName, Map data)` method to `vars/stagefy.groovy` — errors if: data is null (template not found), `data.template` is null (not a template), `data.template.arguments` is null or empty, `data.steps` is null, or `data.stages != null || data.parallels != null` (structural elements forbidden); all errors name the template
- [x] T005 Add `@NonCPS validateBindings(String templateName, List declaredArgs, Map bindings)` method to `vars/stagefy.groovy` — iterates `declaredArgs`; calls `error("Template '${templateName}' requires argument '${arg}' but it was not supplied")` for the first missing key in `bindings`
- [x] T006 Modify `run()` method in `vars/stagefy.groovy` — add `if (temp.template != null) { this.script.error("Template stage '${this.stagename}' cannot be executed directly via run() or use directive") }` as the first check before the existing `stages/parallels/steps` dispatch

**Checkpoint**: All foundational helpers exist. User story phases may now begin.

---

## Phase 3: User Story 1 — Template Declaration and Direct Instantiation (Priority: P1) 🎯 MVP

**Goal**: Authors can define a template stage with `arguments:` + `steps:`, then reference it once in `parallels`, `stages`, or `steps` with explicit variable bindings. Parallels/stages generate a new stage; steps inlines without a new stage.

**Independent Test**: A Jenkins.yml with one template and three references (one in parallels, one in stages, one in steps) runs correctly, substituting argument values in each context.

### Implementation for User Story 1

- [x] T007 [US1] Modify `steps_run()` in `vars/stagefy.groovy` — in the step iteration loop, add handling for `each.containsKey('template')`: load template data via `load_data(filename, each['template'])`, call `validateTemplateData`, build bindings map from all keys except `template`, call `validateBindings`, call `resolveStepsWithBindings`, then execute each resolved step inline (reuse existing sh/script/use/etc. dispatch)
- [x] T008 [US1] Modify `parallels_run()` in `vars/stagefy.groovy` — after existing string-entry handling, add `else if (each instanceof Map && each.containsKey('template'))` branch: load template, validate, build bindings, resolve steps, add closure `{ script.stage(templateName) { <execute resolved steps> } }` to the parallel `data` map using template name as key
- [x] T009 [US1] Modify `stages_run()` in `vars/stagefy.groovy` — after existing string-entry handling, add `else if (each instanceof Map && each.containsKey('template'))` branch: load template, validate, build bindings, resolve steps, wrap in `script.stage(templateName) { <execute resolved steps> }` and append to the `inside` execution list
- [x] T010 [P] [US1] Create `examples/test_templates_direct.yaml` — define template `compile_target` with `arguments: [TARGET]` and `steps: [sh: "echo building {TARGET}"]`; define stage `test_direct_parallels` using it in `parallels` with `TARGET: arm64` and `TARGET: x86_64`; define `test_direct_stages` using it in `stages`; define `test_direct_steps` with a `steps` block that includes a direct template reference and surrounding `sh` steps to verify inline behavior
- [x] T011 [US1] Add test stages to `examples/Jenkinsfile.test_templates` for direct template references — run `stagefy.run('examples/test_templates_direct.yaml', 'test_direct_parallels')`, `test_direct_stages`, and `test_direct_steps`; add error-path test calling template directly via `run()` and asserting it fails

**Checkpoint**: User Story 1 fully functional. A template can be defined and instantiated directly in all three contexts.

---

## Phase 4: User Story 2 — Iterated Generation over Inline Scalar List (Priority: P2)

**Goal**: Authors can use `iterated: { template: X, over: [a, b, c] }` in `parallels`, `stages`, or `steps`. Each scalar item generates one stage (or inlined step block) with the template's single argument bound to that value.

**Independent Test**: `iterated` with a 3-item scalar list produces 3 stages named `templateName_value` in parallel context and 3 sequential stages in stages context.

### Implementation for User Story 2

- [x] T012 [US2] Add `@NonCPS buildStageNameForScalar(String templateName, String value)` method to `vars/stagefy.groovy` — returns `"${templateName}_${value}"`
- [x] T013 [US2] Add `@NonCPS expandIteratedOver(def over)` method to `vars/stagefy.groovy` — if `over instanceof List` return it as-is (handles both scalar and map list inline); if `over instanceof String && over.startsWith('env.')` stub with `error("env.VAR_NAME iteration not yet implemented")` (completed in US4); otherwise call `error("iterated.over must be a list or env.VAR_NAME string, got: ${over.getClass().simpleName}")`; always convert result to plain `ArrayList` before returning (CPS serialization safety)
- [x] T014 [US2] Extend `parallels_run()` in `vars/stagefy.groovy` — add `else if (each instanceof Map && each.containsKey('iterated'))` branch: extract `iteratedData`, load template, call `validateTemplateData`, call `expandIteratedOver(iteratedData.over)`; for each item in result that `instanceof String`: validate template has exactly one declared arg (error if more), build single-entry binding map, call `validateBindings`, call `resolveStepsWithBindings`, add stage closure to parallel `data` map using `buildStageNameForScalar(templateName, item)` as key
- [x] T015 [US2] Extend `stages_run()` in `vars/stagefy.groovy` — add `else if (each instanceof Map && each.containsKey('iterated'))` branch with same expansion logic as T014; append each resolved stage as a sequential `script.stage(stageName) { ... }` to the execution list in list order
- [x] T016 [US2] Extend `steps_run()` in `vars/stagefy.groovy` — add `else if (each.containsKey('iterated'))` branch: expand `over`, for each scalar item resolve bindings and execute the template steps inline (no new stage); repeat for each item in order
- [x] T017 [P] [US2] Create `examples/test_templates_iterated_scalar.yaml` — define single-arg template `run_suite` with `arguments: [SUITE]` and `steps: [sh: "echo running suite {SUITE}"]`; define stage `test_scalar_parallels` using `iterated` over `[unit, integration, e2e]` in parallels; define `test_scalar_stages` using same in stages; define `test_scalar_steps` using it in steps with surrounding steps; define `test_scalar_empty` using `over: []`
- [x] T018 [US2] Add test stages to `examples/Jenkinsfile.test_templates` for scalar iterated blocks — run all four test stages from T017; assert correct stage name pattern in output; verify empty `over:` runs without error

**Checkpoint**: User Story 2 fully functional. Scalar list iteration works in all three contexts.

---

## Phase 5: User Story 3 — Iterated Generation over Inline Map List (Priority: P3)

**Goal**: Authors can use `iterated: { template: X, over: [{A: x, B: y, _name: label}, ...] }` to iterate with multiple argument bindings per item. Stage names use `_name` if present, otherwise a 0-based index suffix.

**Independent Test**: `iterated` with a 2-item map list over a multi-argument template produces 2 stages, each with both arguments correctly substituted and stage names following the `templateName_name` or `templateName_0` pattern.

### Implementation for User Story 3

- [x] T019 [US3] Add `@NonCPS buildStageNameForMap(String templateName, Map entry, int index)` method to `vars/stagefy.groovy` — returns `"${templateName}_${entry._name}"` if `entry.containsKey('_name')`, else `"${templateName}_${index}"`
- [x] T020 [US3] Extend `parallels_run()`, `stages_run()`, `steps_run()` in `vars/stagefy.groovy` — within the existing `iterated:` branch added in T014/T015/T016, add `else if (item instanceof Map)` check alongside the existing scalar check: strip `_name` key from item to produce the bindings map, call `validateBindings`, call `resolveStepsWithBindings`, use `buildStageNameForMap` for stage naming in parallels/stages contexts
- [x] T021 [P] [US3] Create `examples/test_templates_iterated_map.yaml` — define multi-arg template `build_combo` with `arguments: [TARGET, MODE]` and `steps: [sh: "echo building {TARGET} in {MODE} mode"]`; define `test_map_parallels` using `iterated` over a 3-item map list (entries with and without `_name:`); define `test_map_stages` and `test_map_steps` similarly
- [x] T022 [US3] Add test stages to `examples/Jenkinsfile.test_templates` for map iterated blocks — run all test stages from T021; verify multi-arg substitution, `_name` in stage names, index fallback when `_name` absent

**Checkpoint**: User Story 3 fully functional. Map list iteration works, enabling multi-argument dynamic stage generation from inline data.

---

## Phase 6: User Story 4 — Iterated Generation over Environment Variable (Priority: P4)

**Goal**: Authors can use `over: env.VAR_NAME`. The value is resolved at runtime and auto-detected: JSON array of objects → map list; JSON array of strings → scalar list; otherwise → comma-split scalar list.

**Independent Test**: With `PLATFORMS=arm64,x86_64` set, `over: env.PLATFORMS` on a single-arg template generates 2 stages. With `MATRIX=[{"TARGET":"arm64","MODE":"debug"},...]`, `over: env.MATRIX` on a multi-arg template generates matching stages.

### Implementation for User Story 4

- [x] T023 [US4] Replace the `env.VAR_NAME` stub in `expandIteratedOver` in `vars/stagefy.groovy` — extract `varName` from the string after `"env."`; call `this.script.env[varName]`; if null or blank return `[]`; otherwise attempt `new groovy.json.JsonSlurper().parseText(value)`: if result is a List of Maps return as map list (converted to plain `ArrayList<LinkedHashMap>`), if result is a List of non-Map items return as scalar string list; on `JsonException` or any parse error fall back to `value.split(',').collect { it.trim() }.findAll { it }` as scalar string list
- [x] T024 [P] [US4] Create `examples/test_templates_iterated_env.yaml` — define single-arg template `build_platform` and multi-arg template `run_combo`; define stage `test_env_scalar` using `over: env.PLATFORMS`; define `test_env_json_scalar` using `over: env.PLATFORMS_JSON` (JSON array of strings); define `test_env_map` using `over: env.MATRIX` with the multi-arg template; define `test_env_empty` using `over: env.EMPTY_VAR`
- [x] T025 [US4] Add test stages to `examples/Jenkinsfile.test_templates` for env var iterated blocks — set `PLATFORMS=arm64,x86_64`, `PLATFORMS_JSON=["arm64","x86_64"]`, `MATRIX=[{"TARGET":"arm64","MODE":"debug"},{"TARGET":"x86_64","MODE":"release"}]`, `EMPTY_VAR=` before running test stages from T024; verify correct stage counts and argument substitution for each format

**Checkpoint**: All four user stories fully functional. Any `over:` format — static or dynamic — works in all three contexts.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Hardening, error quality, and user-facing examples.

- [x] T026 Add duplicate stage name detection to `parallels_run()` in `vars/stagefy.groovy` — after collecting all stage names (from string entries, template refs, and iterated expansions) into the parallel `data` map keys, check for collisions; call `error("Duplicate stage name '${name}' in parallels block of '${this.stagename}'")` before calling `script.parallel(data)`
- [x] T027 [P] Add stage name sanitization helper `@NonCPS sanitizeStageName(String name)` to `vars/stagefy.groovy` — replaces characters invalid in Jenkins stage names (spaces, slashes, special chars) with underscores; apply in `buildStageNameForScalar` and `buildStageNameForMap`
- [x] T028 [P] Add a realistic template usage example to `examples/Jenkins.yml` — add a commented example section showing a `compile_target` template definition and an `iterated:` usage in a `parallels` block with `over: env.PLATFORMS`
- [x] T029 Audit and harden all error messages added in T004–T025 in `vars/stagefy.groovy` — ensure each message includes: (a) the template name, (b) the argument or field name where applicable, (c) the context (parallels/stages/steps), and (d) what was expected vs what was found

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — **BLOCKS all user story phases**
- **Phase 3 (US1)**: Depends on Phase 2 — no dependencies on US2/US3/US4
- **Phase 4 (US2)**: Depends on Phase 2 — no dependencies on US1/US3/US4
- **Phase 5 (US3)**: Depends on Phase 2 — builds on iterated infrastructure from US2 (depends on T013)
- **Phase 6 (US4)**: Depends on Phase 2 and T013 — extends `expandIteratedOver` from US2
- **Phase 7 (Polish)**: Depends on all user story phases complete

### User Story Dependencies

- **US1**: Independent after Foundational
- **US2**: Independent after Foundational (introduces `expandIteratedOver` and `buildStageNameForScalar`)
- **US3**: Depends on US2 (T013 `expandIteratedOver` must exist; T014/T015/T016 iterated branch must exist)
- **US4**: Depends on US2 (extends T013 `expandIteratedOver`)

### Within Each Phase

- Implementation tasks in `vars/stagefy.groovy` must run sequentially (same file)
- Test fixture creation (`examples/*.yaml`) can run in parallel [P] with the `vars/stagefy.groovy` work in the same phase
- Jenkinsfile test runner updates depend on both the implementation AND the fixture being complete

### Parallel Opportunities Per Story

```
# US1 (Phase 3) — parallel opportunity:
stagefy.groovy changes (T007 → T008 → T009)  [sequential, same file]
examples/test_templates_direct.yaml (T010)    [parallel with T007-T009]

# US2 (Phase 4) — parallel opportunity:
stagefy.groovy changes (T012 → T013 → T014 → T015 → T016)  [sequential]
examples/test_templates_iterated_scalar.yaml (T017)          [parallel with T012-T016]

# US3 (Phase 5) — parallel opportunity:
stagefy.groovy changes (T019 → T020)                         [sequential]
examples/test_templates_iterated_map.yaml (T021)             [parallel with T019-T020]

# US4 (Phase 6) — parallel opportunity:
stagefy.groovy changes (T023)                                 [sequential]
examples/test_templates_iterated_env.yaml (T024)             [parallel with T023]
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001)
2. Complete Phase 2: Foundational (T002–T006) — **required before anything else**
3. Complete Phase 3: User Story 1 (T007–T011)
4. **STOP and VALIDATE**: Run `examples/Jenkinsfile.test_templates` on Jenkins with only US1 test stages active
5. Direct template instantiation is now usable in production Jenkins.yml files

### Incremental Delivery

1. Setup + Foundational → core helpers ready
2. US1 → direct template references work → demo/validate
3. US2 → scalar list iteration works → demo/validate
4. US3 → map list iteration works → demo/validate
5. US4 → env variable iteration works → demo/validate
6. Polish → production-hardened

### Notes

- All 29 tasks modify at most 2 files (`vars/stagefy.groovy` and one `examples/` file per phase)
- No new dependencies are introduced — `groovy.json.JsonSlurper` is already imported
- Existing Jenkins.yml files are fully backward-compatible; no migration required
- Test validation requires a running Jenkins instance; YAML fixtures can be authored and reviewed offline

# Tasks: OOP Refactoring of Stagefy

**Input**: Design documents from `/specs/003-oop-refactoring/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/public-api.md, quickstart.md

**Tests**: Not explicitly requested in spec (out of scope). Test tasks omitted.

**Organization**: Tasks grouped by user story. All changes target `vars/stagefy.groovy`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different classes, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- All file paths reference `vars/stagefy.groovy` unless noted otherwise

---

## Phase 1: Setup

**Purpose**: Prepare the refactoring foundation without changing behavior

- [x] T001 Back up current `vars/stagefy.groovy` to `vars/stagefy.groovy.bak` for rollback reference
- [x] T002 Create `JenkinsContext` class in `vars/stagefy.groovy` — adapter wrapping all Jenkins DSL calls (`stage`, `sh`, `parallel`, `node`, `withEnv`, `readYaml`, `load`, `error`, `env[]`, `setProperty`, `println`) with a `script` field storing the pipeline context

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Abstract base classes and utility classes that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T003 Create abstract `Stage` class in `vars/stagefy.groovy` — fields: `filename`, `stagename`, `parent`, `flag`, `jenkins` (JenkinsContext); methods: `run()` (load data, evaluate `when:`, propagate parent flag, dispatch to subclass via StageFactory), `load()` (read YAML via `jenkins.readYaml()`), `checkCircularLoop(Stage other)` (walk parent chain, error on cycle)
- [x] T004 Create abstract `Step` class in `vars/stagefy.groovy` — fields: `jenkins`, `stage`, `raw`, `stageData`; abstract method: `run()`
- [x] T005 [P] Create `StageFactory` class in `vars/stagefy.groovy` — static `create(filename, stagename, parent, jenkins, data)` dispatching on YAML keys: `stages`→SequentialStage, `parallels`→ParallelStage, `steps`→StepsStage, `template`→error("cannot execute directly"), else→error listing valid keys (`stages`, `parallels`, `steps`)
- [x] T006 [P] Create `StepFactory` class in `vars/stagefy.groovy` — static `create(step, jenkins, stage, stageData)` dispatching on step keys: `sh`→ShStep, `script`→ScriptStep, `setEnvFromFile`→SetEnvFromFileStep, `evaluate`→EvaluateStep, `use`→UseStageStep, `template`→TemplateStep, `iterated`→IteratedStep, else→error listing valid keys
- [x] T007 [P] Create `EnvResolver` class in `vars/stagefy.groovy` — `@NonCPS` static `resolve(String text, envMap)` replacing `${env.VAR}` patterns with values from envMap
- [x] T008 [P] Create `ModuleResolver` class in `vars/stagefy.groovy` — `@NonCPS` static `buildPrefix(List modules)` returning `"set +x; source $MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load <joined>; set -x;"` or empty string if null
- [x] T009 [P] Create `StageNameBuilder` class in `vars/stagefy.groovy` — `@NonCPS` static methods: `sanitize(name)` replacing `[^A-Za-z0-9_.-]+` with `_`; `forScalar(templateName, value)`; `forMap(templateName, entry, index)` using `_name` if present; `forTemplateRef(templateName, bindings, seenNames)` with conflict resolution
- [x] T010 [P] Create `TemplateResolver` class in `vars/stagefy.groovy` — `@NonCPS` static methods: `substituteBindings(String text, Map bindings)` with recursive resolution (loop up to 5 passes, stop when no `{KEY}` tokens remain or no changes, error on depth limit with remaining resolvable tokens); `resolveStepsWithBindings(List steps, Map bindings)` applying substituteBindings to all string values in step maps
- [x] T011 [P] Create `IterationResolver` class in `vars/stagefy.groovy` — static `expandOver(over, jenkins)` resolving `over:` to list (inline list→as-is, `"env.VAR"`→JSON parse then comma-split fallback, convert LazyMap→LinkedHashMap); static `buildBindingsFromItem(templateName, declaredArgs, item)` building bindings from scalar or map item
- [x] T012 [P] Create `Template` class in `vars/stagefy.groovy` — fields: `name`, `filename`, `data`, `arguments`, `steps`; methods: `validate()` (check template key, arguments non-empty, steps present, no stages/parallels, no nested template/iterated in steps), `validateBindings(Map bindings)` (check all declared args present)

**Checkpoint**: All base classes and utilities ready. User story implementation can begin.

---

## Phase 3: User Story 1 — Backward Compatibility (Priority: P1) 🎯 MVP

**Goal**: Refactor internal structure while maintaining 100% backward compatibility with existing pipelines

**Independent Test**: All existing test YAML files (`test_use_inline.yaml`, `test_use_dag.yaml`, `test_use_circular.yaml`, `test_use_makestage.yaml`) and `Jenkinsfile.test_templates` execute with identical behavior

### Implementation for User Story 1

- [x] T013 [P] [US1] Create `ShStep` class in `vars/stagefy.groovy` — `run()`: resolve `${env.VAR}` via EnvResolver, prepend module prefix via ModuleResolver, call `jenkins.sh()`
- [x] T014 [P] [US1] Create `ScriptStep` class in `vars/stagefy.groovy` — `run()`: call `jenkins.load(path).main()`
- [x] T015 [P] [US1] Create `SetEnvFromFileStep` class in `vars/stagefy.groovy` — `run()`: read YAML, set each env key via `jenkins.setEnvProperty()`
- [x] T016 [P] [US1] Create `EvaluateStep` class in `vars/stagefy.groovy` — `run()`: call `jenkins.evaluate(expression)`
- [x] T017 [P] [US1] Create `UseStageStep` class in `vars/stagefy.groovy` — `run()`: parse `"StageName from filepath"`, construct child stage via StageFactory, checkCircularLoop, execute with/without stage wrapper based on `makeStage`
- [x] T018 [US1] Create `StepsStage` class in `vars/stagefy.groovy` — `stepsRun()`: resolve template/iterated steps via StepFactory, execute each step, wrap with `withEnv()`/`node()` as needed; handle `modules:` via ModuleResolver
- [x] T019 [US1] Create `SequentialStage` class in `vars/stagefy.groovy` — `stagesRun()`: iterate entries (string refs, template maps, iterated maps), build execution plan, execute sequentially; duplicate name detection via seenNames list
- [x] T020 [US1] Create `ParallelStage` class in `vars/stagefy.groovy` — `parallelsRun()`: iterate entries (string refs, template maps, iterated maps), build closure map, duplicate name detection, call `jenkins.parallel(data)`
- [x] T021 [US1] Rewrite global functions in `vars/stagefy.groovy` — `run(filename, stagename)`: create JenkinsContext, create root Stage via StageFactory, execute in `stage()` block; `construct_stage()`, `load_data()`, `evaluation()`, `setEnvFromFile()`: delegate to JenkinsContext, preserve exact signatures
- [x] T022 [US1] Remove old monolithic `Stagefy` class from `vars/stagefy.groovy` after all new classes are in place and global functions rewired

**Checkpoint**: Existing YAML files and Jenkinsfiles produce identical results. MVP complete.

---

## Phase 4: User Story 2 — Code Reuse Through Layer Separation (Priority: P1)

**Goal**: Enable developers to instantiate Stage/Step objects directly without using `run()` API

**Independent Test**: A developer can create a StepsStage with JenkinsContext and stage data, configure it, and execute it programmatically

### Implementation for User Story 2

- [x] T023 [US2] Ensure Stage constructors in `vars/stagefy.groovy` accept `(filename, stagename, parent, jenkins)` and are publicly accessible — verify StepsStage, SequentialStage, ParallelStage can be instantiated directly with a JenkinsContext
- [x] T024 [US2] Ensure Step constructors in `vars/stagefy.groovy` accept `(jenkins, stage, raw, stageData)` and are publicly accessible — verify all Step subclasses can be instantiated and `run()` called independently

**Checkpoint**: Individual Stage and Step objects are composable and usable outside `run()`.

---

## Phase 5: User Story 3 — Reduce Code Complexity (Priority: P1)

**Goal**: Each class has single responsibility; cyclomatic complexity ≤ 5 per method

**Independent Test**: Each class handles exactly one concern; no method exceeds cyclomatic complexity of 5

### Implementation for User Story 3

- [x] T025 [US3] Review and refactor `StepsStage.stepsRun()` in `vars/stagefy.groovy` — extract step resolution loop into a private `resolveSteps()` method; extract env/node wrapping into a private `wrapExecution()` method; ensure each method has cyclomatic complexity ≤ 5
- [x] T026 [US3] Review and refactor `SequentialStage.stagesRun()` and `ParallelStage.parallelsRun()` in `vars/stagefy.groovy` — extract entry-type dispatch into a shared private helper if common logic exists; ensure cyclomatic complexity ≤ 5 per method
- [x] T027 [US3] Add class-level Groovydoc comments to all classes in `vars/stagefy.groovy` — document purpose, CPS serialization strategy (Serializable vs @NonCPS), and extension points

**Checkpoint**: All classes have single responsibility with clear documentation.

---

## Phase 6: User Story 4 — Template Instantiation in All Contexts (Priority: P1)

**Goal**: Templates work consistently in steps, stages, and parallels contexts

**Independent Test**: Template steps, template stages (sequential/parallel), and iterated templates all execute correctly with proper variable substitution

### Implementation for User Story 4

- [x] T028 [P] [US4] Create `TemplateStep` class in `vars/stagefy.groovy` — `run()`: load template via `stage.load()`, create Template object, validate, build bindings from step map (all keys except `template`), resolve steps via TemplateResolver, execute each resolved step via StepFactory
- [x] T029 [P] [US4] Create `IteratedStep` class in `vars/stagefy.groovy` — `run()`: load template, create Template, validate, expand `over:` via IterationResolver, for each item: buildBindingsFromItem, validateBindings, resolveSteps, execute inline
- [x] T030 [US4] Verify template handling in `SequentialStage` and `ParallelStage` in `vars/stagefy.groovy` — ensure `template:` and `iterated:` map entries in `stages:` and `parallels:` blocks create proper child stages with resolved steps, using StageNameBuilder for unique names

**Checkpoint**: Templates work identically in all three contexts (steps, stages, parallels).

---

## Phase 7: User Story 5 — Improved Error Messages (Priority: P2)

**Goal**: Validation failures produce specific, actionable error messages

**Independent Test**: Missing template args, circular references, and invalid directives produce clear error messages with problem location and fix suggestion

### Implementation for User Story 5

- [x] T031 [US5] Enhance `Template.validate()` and `Template.validateBindings()` in `vars/stagefy.groovy` — error messages must list all required arguments by name, show which stage triggered the error, and suggest the correct format
- [x] T032 [US5] Enhance `Stage.checkCircularLoop()` in `vars/stagefy.groovy` — error message must show the full circular chain (e.g., "A → B → A") instead of just the final conflict
- [x] T033 [US5] Verify StageFactory and StepFactory fail-fast messages in `vars/stagefy.groovy` — ensure unknown directive errors list the unrecognized key and all valid options

**Checkpoint**: All error paths produce actionable messages.

---

## Phase 8: User Story 6 — Future Extensibility (Priority: P2)

**Goal**: Clear extension points for new step/stage types

**Independent Test**: A new step type can be added by extending Step and registering in StepFactory without modifying core logic

### Implementation for User Story 6

- [x] T034 [US6] Add `DEBUG_LEVEL` logging support in `vars/stagefy.groovy` — read `env.DEBUG_LEVEL` in `Stage.run()` (default 0); level ≥1: println stage entry/exit in Stage subclasses; level ≥2: println step execution detail in Step subclasses
- [x] T035 [US6] Verify extension points in `vars/stagefy.groovy` — confirm StepFactory and StageFactory dispatch can be extended by adding a new `else if` branch and a new class without modifying existing classes

**Checkpoint**: DEBUG_LEVEL works; extension pattern is clear and documented.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [x] T036 Remove `vars/stagefy.groovy.bak` backup file created in T001
- [ ] T037 Validate all existing example YAML files execute correctly with refactored code — run through `examples/test_use_inline.yaml`, `examples/test_use_dag.yaml`, `examples/test_use_circular.yaml`, `examples/test_use_makestage.yaml`, `examples/Jenkinsfile.test_templates`
- [ ] T038 Run `specs/003-oop-refactoring/quickstart.md` validation — verify documented behavior matches implementation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 (T002 JenkinsContext must exist)
- **Phase 3 (US1)**: Depends on Phase 2 — all base classes and utilities must exist
- **Phase 4 (US2)**: Depends on Phase 3 — classes must be implemented first
- **Phase 5 (US3)**: Depends on Phase 3 — refactoring requires working code
- **Phase 6 (US4)**: Depends on Phase 3 — template classes need Stage/Step infrastructure
- **Phase 7 (US5)**: Depends on Phases 3+6 — error paths need working classes
- **Phase 8 (US6)**: Depends on Phase 3 — extension points need base structure
- **Phase 9 (Polish)**: Depends on all previous phases

### User Story Dependencies

- **US1 (Backward Compat)**: Blocks all other stories — must complete first
- **US2 (Code Reuse)**: Depends on US1 only
- **US3 (Complexity)**: Depends on US1 only
- **US4 (Templates)**: Depends on US1 only
- **US5 (Error Messages)**: Depends on US1 + US4
- **US6 (Extensibility)**: Depends on US1 only

### Within Phase 2 (Foundational) — Parallel Opportunities

```
T003 (Stage base)  ─┐
T004 (Step base)   ─┤── sequential (T003/T004 first)
                    │
T005 (StageFactory) ┤
T006 (StepFactory)  ┤
T007 (EnvResolver)  ┤
T008 (ModuleResolver)┤── all [P] parallel after T003/T004
T009 (StageNameBuilder)┤
T010 (TemplateResolver)┤
T011 (IterationResolver)┤
T012 (Template)     ┘
```

### Within Phase 3 (US1) — Parallel Opportunities

```
T013-T017 (simple Steps) ── all [P] parallel
T018-T020 (Stage subclasses) ── sequential (depend on Steps)
T021 (global functions) ── after T018-T020
T022 (remove old class) ── after T021
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T002)
2. Complete Phase 2: Foundational (T003-T012)
3. Complete Phase 3: User Story 1 (T013-T022)
4. **STOP and VALIDATE**: Run all existing test YAMLs — must produce identical results
5. MVP is deployable at this point

### Incremental Delivery

1. Setup + Foundational → base ready
2. US1 (backward compat) → **MVP — deploy/validate**
3. US2 + US3 + US4 (can parallel) → enhanced architecture
4. US5 + US6 → polish and extensibility
5. Phase 9 → final validation

---

## Notes

- All changes target single file: `vars/stagefy.groovy`
- [P] tasks within same phase can run in parallel
- CPS rule: Stage/Step classes = `Serializable`; utility classes = `@NonCPS` stateless
- Commit after each phase completion for safe rollback points

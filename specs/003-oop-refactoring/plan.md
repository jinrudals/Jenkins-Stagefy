# Implementation Plan: OOP Refactoring of Stagefy

**Branch**: `003-oop-refactoring` | **Date**: 2026-04-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-oop-refactoring/spec.md`

## Summary

Refactor the monolithic `Stagefy` class (~400 lines) into a well-structured OOP hierarchy with Stage layer (StepsStage, SequentialStage, ParallelStage), Step layer (ShStep, ScriptStep, UseStageStep, TemplateStep, IteratedStep, etc.), Template system, JenkinsContext adapter, and factory/utility classes. All changes are internal to `vars/stagefy.groovy` — the public API and YAML schema remain 100% backward compatible.

## Technical Context

**Language/Version**: Groovy (Jenkins Shared Library, Jenkins Pipeline DSL)
**Primary Dependencies**: Jenkins Pipeline: Utility Steps Plugin (`readYaml`), `org.jenkinsci.plugins.pipeline.modeldefinition.Utils`
**Storage**: YAML files — read via `readYaml` at pipeline runtime
**Testing**: Jenkinsfile-based integration tests + YAML fixture files
**Target Platform**: Jenkins CI/CD server
**Project Type**: Jenkins Shared Library (`vars/stagefy.groovy`)
**Performance Goals**: ±5% of original execution time (no regression)
**Constraints**: Jenkins CPS serialization; `@NonCPS` for pure helpers; single file (`vars/stagefy.groovy`)
**Scale/Scope**: Single file refactoring; ~400 lines → ~15 classes in same file

## Constitution Check

*Constitution file is unpopulated (template only). No constitutional gates to evaluate.*

Post-design check: refactoring is internal — no existing interfaces broken, no new dependencies introduced, backward-compatible with all current YAML files and Jenkinsfiles.

## Project Structure

### Documentation (this feature)

```text
specs/003-oop-refactoring/
├── plan.md              # This file
├── research.md          # Phase 0: design decisions
├── data-model.md        # Phase 1: entity definitions
├── quickstart.md        # Phase 1: usage guide
├── contracts/
│   └── public-api.md    # Phase 1: public API contract
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code

```text
vars/
└── stagefy.groovy       # All changes — single file refactoring
```

**Structure Decision**: All classes remain in `vars/stagefy.groovy`. Moving to `src/` would change Jenkins library loading and risk breaking existing `@Library` imports. The refactoring goal is internal restructuring, not changing the library's external interface.

## Implementation Design

### Class Organization (within `vars/stagefy.groovy`)

Order of classes in file:

1. **JenkinsContext** — adapter wrapping all Jenkins DSL calls
2. **Stage** (abstract) — base class with load, when, circular check
3. **StepsStage** — step execution with env/node wrapping
4. **SequentialStage** — sequential stage orchestration
5. **ParallelStage** — parallel stage orchestration
6. **Step** (abstract) — base class for step execution
7. **ShStep, ScriptStep, SetEnvFromFileStep, EvaluateStep** — simple steps
8. **UseStageStep** — stage reference step
9. **TemplateStep** — inline template step
10. **IteratedStep** — iterated template step
11. **Template** — template data and validation
12. **TemplateResolver** — `{ARG}` substitution (recursive, depth 5)
13. **IterationResolver** — `over:` expansion
14. **StageFactory** — YAML → Stage subclass dispatch
15. **StepFactory** — step map → Step subclass dispatch
16. **StageNameBuilder** — unique stage name generation
17. **EnvResolver** — `${env.VAR}` substitution
18. **ModuleResolver** — module load prefix
19. **Global functions** — `run()`, `construct_stage()`, `load_data()`, `evaluation()`, `setEnvFromFile()` (unchanged signatures)

### CPS Serialization Strategy

- **Serializable**: Stage, StepsStage, SequentialStage, ParallelStage, Step subclasses, Template, JenkinsContext — these cross CPS boundaries via closures
- **@NonCPS stateless**: TemplateResolver, IterationResolver, StageFactory, StepFactory, StageNameBuilder, EnvResolver, ModuleResolver — pure computation, never call pipeline steps

### Key Design Decisions

1. **Recursive template substitution** (depth limit 5): `TemplateResolver.substituteBindings()` loops until no `{KEY}` tokens remain or depth exhausted
2. **Fail-fast on unknown directives**: Both factories error with valid key list
3. **DEBUG_LEVEL logging**: Read from `env.DEBUG_LEVEL`; 0=silent, 1=stage, 2=step detail
4. **Single file**: All classes in `vars/stagefy.groovy` for backward compatibility

### Migration Path from Current Code

| Current Method | Moves To |
|---------------|----------|
| `Stagefy.run()` | `Stage.run()` (dispatch via StageFactory) |
| `Stagefy.steps_run()` | `StepsStage.stepsRun()` |
| `Stagefy.parallels_run()` | `ParallelStage.parallelsRun()` |
| `Stagefy.stages_run()` | `SequentialStage.stagesRun()` |
| `Stagefy.executeStep()` | `StepFactory.create()` + `Step.run()` |
| `Stagefy.substituteBindings()` | `TemplateResolver.substituteBindings()` |
| `Stagefy.resolveStepsWithBindings()` | `TemplateResolver.resolveStepsWithBindings()` |
| `Stagefy.validateTemplateData()` | `Template.validate()` |
| `Stagefy.validateBindings()` | `Template.validateBindings()` |
| `Stagefy.expandIteratedOver()` | `IterationResolver.expandOver()` |
| `Stagefy.buildBindingsFromItem()` | `IterationResolver.buildBindingsFromItem()` |
| `Stagefy.buildStageNameFor*()` | `StageNameBuilder.*()` |
| `Stagefy.sanitizeStageName()` | `StageNameBuilder.sanitize()` |
| `Stagefy.check_circular_loop()` | `Stage.checkCircularLoop()` |
| `Stagefy.construct_stage()` | `StageFactory.create()` |
| env pattern matching in `steps_run()` | `EnvResolver.resolve()` |
| module prefix in `steps_run()` | `ModuleResolver.buildPrefix()` |
| Direct `this.script.*` calls | `JenkinsContext.*()` |

### Global Functions (unchanged)

```groovy
def run(filename, stagename)           // creates JenkinsContext, root Stage, executes
def construct_stage(filename, stagename) // creates Stage via factory
def load_data(filename, stagename)     // reads YAML
def evaluation(value)                  // evaluates expression
def setEnvFromFile(filename)           // loads env from YAML
```

## Complexity Tracking

No constitution violations. All changes are internal to one file. No new dependencies.

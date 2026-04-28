# Feature Specification: OOP Refactoring of Stagefy

**Feature Branch**: `003-oop-refactoring`
**Created**: 2026-04-28
**Status**: Draft
**Input**: User description: Refactor Stagefy from monolithic to OOP architecture with Stage Layer (StepsStage, SequentialStage, ParallelStage) and Step Layer (ShStep, ScriptStep, etc.)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Maintain Backward Compatibility with Existing Pipelines (Priority: P1)

A Jenkins pipeline administrator has existing Jenkinsfiles and YAML configurations using the current Stagefy `run()` API. After the refactoring, they should be able to continue using these pipelines without modification while benefiting from the improved internal architecture.

**Why this priority**: P1 - Any breaking change would prevent adoption and require users to rewrite all existing pipelines. Backward compatibility ensures a smooth migration path.

**Independent Test**: Existing test Jenkinsfiles (test_use_inline.yaml, test_use_dag.yaml, test_use_circular.yaml, test_use_makestage.yaml) should execute successfully with the refactored code, producing identical results to the original implementation.

**Acceptance Scenarios**:

1. **Given** an existing Jenkinsfile calling `run(filename, stagename)`, **When** the refactored Stagefy is used, **Then** the pipeline executes with the same behavior as before
2. **Given** a YAML configuration with stages, parallels, and steps, **When** loaded and executed, **Then** the execution order and stage output remain identical
3. **Given** a use directive with `makeStage: false`, **When** executed, **Then** the stage is created or omitted correctly as before
4. **Given** environment variable substitution in steps, **When** executed, **Then** variables are resolved correctly

---

### User Story 2 - Enable Code Reuse Through Proper Layer Separation (Priority: P1)

A Groovy developer wants to import and reuse individual components (stages, steps, templates) from the Stagefy library in custom code without being forced to use the monolithic `run()` API. The refactored architecture should expose well-defined, composable classes.

**Why this priority**: P1 - Code reuse and composability are core benefits of OOP; without this, the refactoring loses its main value proposition. This enables future features and custom extensions.

**Independent Test**: A developer can instantiate a Stage object directly, configure it with custom parameters, and execute it without using the public `run()` function. They can also compose Step objects independently for custom workflows.

**Acceptance Scenarios**:

1. **Given** a JenkinsContext and stage data, **When** a developer instantiates a StepsStage, SequentialStage, or ParallelStage directly, **Then** the stage can be configured and executed programmatically
2. **Given** a template and bindings, **When** a developer creates a TemplateInstance, **Then** the template can be resolved and executed with custom parameters
3. **Given** individual Step objects, **When** composed in custom code, **Then** they execute in the correct order with proper error handling

---

### User Story 3 - Reduce Code Complexity and Improve Maintainability (Priority: P1)

A maintainer of the Stagefy codebase struggles with a 400+ line monolithic class where stage execution logic, step handling, template resolution, and utility functions are all intertwined. The refactored OOP structure should split concerns into focused, testable classes, making the codebase easier to understand and extend.

**Why this priority**: P1 - Code complexity is a technical debt that impacts maintainability, testing, and future feature development. Clear layer separation (Stage, Step, Template) directly addresses this.

**Independent Test**: The refactored code should be organizable into logical modules where each class (StepsStage, ShStep, Template, etc.) has a single, well-defined responsibility. The code should be measurably easier to test through unit tests on individual classes.

**Acceptance Scenarios**:

1. **Given** the refactored Stagefy code, **When** reviewed, **Then** Stage classes handle only structural orchestration (stages, parallels, steps dispatch)
2. **Given** the Step hierarchy, **When** examined, **Then** each Step subclass handles exactly one step type (sh, script, use, template, etc.)
3. **Given** Template-related code, **When** reviewed, **Then** template loading, validation, and binding resolution are separated from stage execution

---

### User Story 4 - Support Template Instantiation in All Contexts (Priority: P1)

A pipeline writer uses templates in multiple contexts: inline steps, sequential stages, parallel branches, and iterated variants. The refactored architecture should enable templates to work consistently across all contexts without special-case code in each handler.

**Why this priority**: P1 - Templates are a core feature; inconsistent behavior across contexts creates user confusion and maintenance burden. This ensures feature completeness.

**Independent Test**: Template steps, template stages (sequential/parallel), and iterated templates should all execute correctly in their respective contexts with proper variable substitution and error handling.

**Acceptance Scenarios**:

1. **Given** a template step in a steps block, **When** executed, **Then** the template is instantiated with bindings and steps are inlined
2. **Given** a template entry in stages or parallels, **When** executed, **Then** a new stage is created with the resolved steps
3. **Given** an iterated template with `over: env.VAR`, **When** the environment variable is JSON, **Then** map entries are bound correctly; when CSV, **Then** scalar binding works
4. **Given** an iterated template with `_name` in map entries, **When** executed, **Then** stage name uses the custom name instead of index

---

### User Story 5 - Improve Error Messages with Clear Failure Diagnostics (Priority: P2)

A pipeline writer encounters an error in their YAML or template configuration. The current error messages sometimes point to the wrong location or don't clearly explain what went wrong. The refactored code should provide specific, actionable error messages that help developers fix issues quickly.

**Why this priority**: P2 - While important for usability, this is secondary to core functionality. Clear error messages significantly improve developer experience but don't block core use cases.

**Independent Test**: Validation failures (missing template arguments, circular references, invalid directives) should produce error messages that clearly indicate the problem and suggest a fix.

**Acceptance Scenarios**:

1. **Given** a template missing a required argument, **When** used, **Then** error message lists all required arguments and their names
2. **Given** a circular stage reference, **When** detected, **Then** error message shows the loop chain (A → B → A)
3. **Given** an invalid use directive, **When** parsed, **Then** error message shows the expected format and the actual input

---

### User Story 6 - Enable Future Optimizations and Extensions (Priority: P2)

The current monolithic structure makes it difficult to add new features (e.g., stage caching, conditional steps, retry logic, new step types). The refactored OOP architecture should provide clear extension points for future enhancements.

**Why this priority**: P2 - While important for long-term maintainability, this doesn't directly impact immediate functionality. It enables future work but isn't required for the initial refactoring.

**Independent Test**: New step types should be addable by extending the Step class; new stage types by extending Stage; new utilities by adding classes to logical packages.

**Acceptance Scenarios**:

1. **Given** the refactored code, **When** a developer wants to add a new step type, **Then** they can extend Step and register in StepFactory without modifying core logic
2. **Given** new utilities needed, **When** required, **Then** new classes can be added to existing packages without breaking existing code

---

### Edge Cases

- What happens when a template is used before it is defined in the same YAML file?
- How does the system handle circular stage references (A uses B, B uses A)?
- What happens when `over: env.VAR` references an undefined environment variable?
- How should the system behave when template arguments contain special characters or newlines?
- What happens when a stage/template name conflicts with a keyword or contains invalid Jenkins stage name characters?
- How are module loading prefixes combined when stages are nested?
- What happens when a template contains multiple nested levels of substitution ({ARG_1} within {ARG_2})?

## Requirements *(mandatory)*

### Functional Requirements

**Architecture & Layer Separation**

- **FR-001**: System MUST define a clear Stage layer with three concrete implementations: StepsStage (executes steps), SequentialStage (orchestrates stages sequentially), and ParallelStage (orchestrates stages in parallel)
- **FR-002**: System MUST define a Step layer with concrete implementations for each step type: ShStep (shell commands), ScriptStep (Groovy scripts), SetEnvFromFileStep, EvaluateStep, UseStageStep (stage references), TemplateStep (inline templates), and IteratedStep (loop expansion)
- **FR-003**: System MUST provide StageFactory and StepFactory for creating appropriate instances based on YAML data
- **FR-004**: System MUST define a JenkinsContext adapter that encapsulates all Jenkins pipeline DSL calls (sh, stage, parallel, readYaml, etc.)

**Template System**

- **FR-005**: System MUST validate templates to ensure they contain only steps (no nested stages or parallels)
- **FR-006**: System MUST validate templates to ensure all declared arguments are supplied when instantiated
- **FR-007**: System MUST support variable substitution in template steps using {ARG_NAME} syntax with recursive resolution — substitution repeats until no `{…}` tokens remain or a depth limit of 5 is reached (to prevent infinite loops from circular references like `{A}→{B}→{A}`)
- **FR-008**: System MUST support direct template references in steps, stages, and parallels blocks
- **FR-009**: System MUST support iterated template expansion with both scalar (comma-separated) and map (JSON) iteration formats
- **FR-010**: System MUST generate unique stage names for iterated templates, using item._name if provided, otherwise deriving from item values or index

**Iteration & Expansion**

- **FR-011**: System MUST support `over: [list]` for inline iteration in steps/stages/parallels
- **FR-012**: System MUST support `over: env.VAR_NAME` where VAR_NAME contains comma-separated values or JSON list/map array
- **FR-013**: System MUST detect JSON format automatically; if not valid JSON, fall back to comma-split
- **FR-014**: System MUST support map iteration for multi-argument templates with automatic binding from map keys to template arguments

**Circular Reference Detection**

- **FR-015**: System MUST detect and prevent circular stage references (stage A uses stage B which uses stage A)
- **FR-016**: System MUST report the circular reference chain in error messages

**Backward Compatibility**

- **FR-017**: System MUST maintain the public API: `run(filename, stagename)`, `construct_stage(filename, stagename)`, `load_data(filename, stagename)`, `evaluation(value)`, `setEnvFromFile(filename)`
- **FR-018**: System MUST execute the same YAML configurations with identical behavior before and after refactoring
- **FR-019**: System MUST support the `use` directive with optional `makeStage: false` parameter
- **FR-020**: System MUST support `modules:` array in stage data for environment setup via module load commands

**Conditional Execution**

- **FR-021**: System MUST support `when:` conditions on stages, evaluated to determine if stage should execute or be skipped
- **FR-022**: System MUST propagate parent stage enabled state to child stages

**Environment & Node Binding**

- **FR-023**: System MUST support `env:` map to set environment variables for a stage via withEnv()
- **FR-024**: System MUST support `node:` label to bind a stage to specific Jenkins agent nodes via node()
- **FR-025**: System MUST compose wrappers correctly when both env and node are specified

**Validation & Error Handling**

- **FR-026**: System MUST validate that stages reference valid stage names that exist in the specified files
- **FR-027**: System MUST validate that templates are not executed directly (only via template: or iterated: directives)
- **FR-028**: System MUST validate that templates contain at least one argument declaration
- **FR-029**: System MUST validate that parallel/stages entries have unique stage names and detect duplicates
- **FR-030**: System MUST provide clear error messages indicating what went wrong, where it happened, and (if applicable) what to fix
- **FR-031**: System MUST support a `DEBUG_LEVEL` environment variable to control logging verbosity of Stage/Step execution tracing (e.g., 0=silent, 1=stage entry/exit, 2=step-level detail)
- **FR-032**: System MUST fail-fast with a clear error message when StageFactory or StepFactory encounters an unrecognized YAML directive, indicating the unknown directive name and expected valid options

### Key Entities *(include if feature involves data)*

**Stage Hierarchy**
- **Stage (abstract)**: Base class representing any executable stage in a Jenkins pipeline
  - filename: String - source YAML file
  - name: String - stage identifier
  - parent: Stage - parent stage for hierarchy tracking
  - enabled: Boolean - execution state
  - Methods: run(), load(), checkCircularLoop()

- **StepsStage (extends Stage)**: Executes a list of steps sequentially within one stage
  - Resolves step directives (template, iterated) before execution

- **SequentialStage (extends Stage)**: Orchestrates multiple child stages in sequence
  - Supports string references, template: directive, and iterated: directive

- **ParallelStage (extends Stage)**: Orchestrates multiple child stages in parallel via Jenkins parallel step
  - Supports string references, template: directive, and iterated: directive

**Step Hierarchy**
- **Step (abstract)**: Base class for any executable step
  - jenkins: JenkinsContext - Jenkins pipeline adapter
  - stage: Stage - parent stage
  - raw: Map - YAML definition of the step
  - stageData: Map - parent stage's YAML data
  - Method: run()

- **ShStep**: Executes shell commands with environment variable and module prefix substitution
- **ScriptStep**: Loads and executes external Groovy scripts
- **SetEnvFromFileStep**: Loads environment variables from a YAML file
- **EvaluateStep**: Evaluates Groovy expressions
- **UseStageStep**: References and executes another stage, optionally creating a new stage wrapper
- **TemplateStep**: Instantiates and inlines a template's resolved steps
- **IteratedStep**: Expands a template multiple times with different bindings

**Template System**
- **Template**: Represents a reusable template stage with argument declarations
  - file: String
  - name: String
  - data: Map - YAML definition
  - arguments: List<String>
  - Methods: validate(), validateBindings(), bindingsFromItem(), resolveSteps()

- **TemplateInstance**: A specific instantiation of a template with bindings
  - template: Template
  - bindings: Map - argument values
  - stageName: String - name for generated stage
  - Method: runInline()

**Jenkins Adapter**
- **JenkinsContext**: Encapsulates all Jenkins DSL calls to enable testing and abstraction
  - script: Object - Jenkins script context
  - Methods: stage(), sh(), parallel(), node(), withEnv(), readYaml(), loadData(), evaluateExpr(), etc.

**Utilities**
- **StageFactory**: Creates appropriate Stage subclass based on YAML structure
- **StepFactory**: Creates appropriate Step subclass based on step directive
- **TemplateFactory**: Loads and validates templates
- **TemplateResolver**: Substitutes template bindings into step definitions
- **IterationResolver**: Expands `over:` directives into iteration lists
- **StageNameBuilder**: Generates unique stage names for iterated templates
- **EnvResolver**: Substitutes environment variables in shell commands
- **ModuleResolver**: Builds module load prefix for shell commands
- **EnvFileLoader**: Loads and applies environment variables from files
- **InlineStepExecutor**: Executes resolved steps from template instances

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **Backward Compatibility**: 100% of existing test YAML files pass without modification and produce identical execution behavior (verified via existing test suite)

- **Code Complexity Reduction**: The refactored code should be organizable into at least 10+ focused classes, each with a single responsibility, compared to the current monolithic design. Each class should have a cyclomatic complexity ≤ 5.

- **Testability**: At least 80% of public methods should be independently unit-testable without requiring Jenkins DSL context. The JenkinsContext adapter enables mock-based testing.

- **API Stability**: The public API surface (run, construct_stage, load_data, evaluation, setEnvFromFile) remains unchanged and compatible with existing code.

- **Documentation**: Code comments and class structure should make the architecture clear enough that a new developer can understand how to extend the system (add new Step types, Stage types, etc.) without reading extensive documentation.

- **Performance**: Refactored execution time on test pipelines should be within ±5% of original implementation (no performance regression from restructuring).

## Clarifications

### Session 2026-04-28

- Q: Which CPS serialization strategy should refactored classes use? → A: Hybrid — core Stage/Step classes implement Serializable; utility classes are @NonCPS-only stateless helpers.
- Q: How should template variable resolution handle nested/recursive references? → A: Recursive until stable — keep resolving until no {…} tokens remain, with a depth limit of 5 to prevent infinite loops.
- Q: How should execution tracing/observability be handled in refactored code? → A: Use a DEBUG_LEVEL environment variable to control logging verbosity of Stage/Step execution tracing.
- Q: How should unknown/unrecognized YAML directives be handled by factories? → A: Fail-fast — immediately abort with a clear error message indicating the unrecognized directive.

## Assumptions *(mandatory)*

- **Groovy Serialization**: Core Stage and Step classes implement `Serializable` for Jenkins CPS compatibility. Utility/helper classes (factories, resolvers, builders) are stateless and annotated with `@NonCPS` only, avoiding serialization concerns. Nested closures and transient data are managed carefully.
- **Jenkins Version**: Codebase targets Jenkins with standard Pipeline plugin and Pipeline Model Definition plugin (readYaml, Utils.markStageSkippedForConditional available)
- **YAML Structure**: YAML files are assumed to be well-formed and readable via Jenkins readYaml; malformed YAML errors are delegated to readYaml
- **Environment Variables**: Environment variable substitution assumes Jenkins env object is available in the script context
- **Module System**: Module loading (if used) assumes `$MODULESHOME` is available and `module load` is a valid command in the Jenkins agent environment
- **Stage Name Sanitization**: Jenkins stage names must not contain special characters; sanitization replaces invalid characters with underscores
- **Circular Reference Depth**: Circular reference detection assumes reasonable nesting depth (not designed for pathological cases with 100+ levels)
- **No Explicit State Management**: The refactored code uses functional composition and closures for state management rather than shared mutable objects to maintain CPS compatibility

## Constraints & Scope Boundaries *(mandatory)*

**In Scope**
- Refactoring the monolithic Stagefy class into a well-structured OOP hierarchy
- Maintaining 100% backward compatibility with existing YAML and API
- Improving code organization and testability
- Supporting all existing features (templates, iteration, circular detection, use directives, etc.)

**Out of Scope**
- Adding new features beyond those in the original specification
- Performance optimizations that require algorithmic changes
- Migration of existing production pipelines (backward compatibility ensures they work as-is)
- Groovy version upgrades or Jenkins version changes
- Unit test suite implementation (though the refactored code should enable testing)
- Documentation website or extensive user guides (code-level documentation only)

**Explicit Non-Goals**
- Rewriting YAML schema or config format (maintaining backward compatibility)
- Replacing Jenkins DSL calls with alternative orchestration tools
- Implementing caching, retry logic, or other advanced pipeline features
- Supporting multiple Jenkins versions with version-specific code paths

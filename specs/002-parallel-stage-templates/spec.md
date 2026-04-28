# Feature Specification: Stage Templates with Iteration

**Feature Branch**: `002-parallel-stage-templates`
**Created**: 2026-04-28
**Status**: Draft
**Input**: User description: "Jenkins.yml에 template 타입 stage를 선언(arguments + steps)하고, parallels/stages/steps 내에서 직접 참조하거나 iterated로 리스트/env 변수를 순회하며 동적으로 stage 또는 step을 생성하는 기능. template은 steps만 포함 가능."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Template Declaration and Direct Instantiation (Priority: P1)

A pipeline author defines a reusable set of steps as a named template stage in Jenkins.yml. The template explicitly declares its arguments and contains only steps. The author then references the template in a `parallels`, `stages`, or `steps` block, supplying concrete values for each argument. References in `parallels` or `stages` generate a new stage; references in `steps` inline the steps into the current stage without creating a new stage.

**Why this priority**: Foundational. Without template declaration and single instantiation, no other part of this feature works. The explicit `arguments:` declaration also enables load-time validation for all downstream stories.

**Independent Test**: A template with two declared arguments referenced once in a `parallels` block and once in a `steps` block produces one new parallel stage and one set of inlined steps, both with correctly substituted values.

**Acceptance Scenarios**:

1. **Given** a template `run_test` with `arguments: [SUITE, TARGET]` and steps referencing both, **When** a `stages` block references `run_test` with `SUITE: unit` and `TARGET: arm64`, **Then** one sequential stage runs with both values substituted.
2. **Given** a `steps` block referencing `run_test` with `SUITE: integration` and `TARGET: x86_64`, **When** the pipeline runs, **Then** the template steps are inlined into the current stage—no new stage is created.
3. **Given** a template reference missing a declared argument, **When** the pipeline is loaded, **Then** a descriptive error names the missing argument and template—nothing executes.
4. **Given** a template reference supplying an argument not declared in the template, **When** the pipeline is loaded, **Then** the extra argument is ignored and execution proceeds normally.
5. **Given** a template definition that contains a nested stage or `parallels` block, **When** the pipeline is loaded, **Then** a descriptive error states that templates may only contain steps.

---

### User Story 2 - Iterated Instantiation over an Inline Scalar List (Priority: P2)

A pipeline author has a single-argument template and a known static list of values. They want to generate one stage (or one inlined step block) per value without writing out each reference manually.

**Why this priority**: The most common dynamic pattern—iterate over platforms, environments, test suites—with a simple list. Builds directly on Story 1.

**Independent Test**: `iterated: { template: build, over: [arm64, x86_64, riscv] }` in a `parallels` block generates exactly 3 parallel stages; in a `steps` block it inlines 3 step copies.

**Acceptance Scenarios**:

1. **Given** `iterated: { template: build, over: [arm64, x86_64, riscv] }` inside `parallels`, **When** the pipeline runs, **Then** three stages execute in parallel—one per item—with the single declared argument bound to each value.
2. **Given** the same `iterated:` inside `stages`, **When** the pipeline runs, **Then** three stages execute sequentially in list order.
3. **Given** the same `iterated:` inside `steps`, **When** the pipeline runs, **Then** the template steps are inlined three times sequentially—no new stages are created.
4. **Given** any context, each generated stage name (for `parallels`/`stages`) MUST incorporate the iterated value and be unique (e.g., `build_arm64`, `build_x86_64`).
5. **Given** `over: []`, **When** the pipeline runs, **Then** nothing is generated and execution continues without error.
6. **Given** a template with more than one declared argument used with a scalar `over:` list, **When** the pipeline is loaded, **Then** a descriptive error states that scalar iteration requires a single-argument template.

---

### User Story 3 - Iterated Instantiation over an Inline Map List (Priority: P3)

A pipeline author has a multi-argument template and wants to supply a different set of variable values per iteration, defined statically in Jenkins.yml.

**Why this priority**: Enables test-matrix-style patterns (e.g., platform × suite combinations) with full control over which combinations to run. Builds on Story 2 by supporting multiple variable bindings per iteration item.

**Independent Test**: `iterated: { template: run_test, over: [{SUITE: unit, TARGET: arm64}, {SUITE: integration, TARGET: x86_64}] }` in a `parallels` block generates exactly 2 parallel stages, each with their respective argument values.

**Acceptance Scenarios**:

1. **Given** `over:` is a list of maps and `parallels` context, **When** the pipeline runs, **Then** one parallel stage is generated per map entry, with each map's keys bound to the corresponding template arguments.
2. **Given** the same in a `stages` context, **When** the pipeline runs, **Then** stages execute sequentially in list order.
3. **Given** the same in a `steps` context, **When** the pipeline runs, **Then** the template steps are inlined once per map entry, sequentially.
4. **Given** a map entry that omits a declared argument, **When** the pipeline is loaded, **Then** a descriptive error names the missing argument and the map entry position.
5. **Given** a `parallels` block mixing explicit `template:` entries and an `iterated:` entry, **When** the pipeline runs, **Then** all resulting stages execute together in parallel.

---

### User Story 4 - Iterated Instantiation over an Environment Variable (Priority: P4)

A pipeline author wants the list of values to be resolved at runtime from the environment (e.g., a Jenkins parameter or upstream job output). This is supported for single-argument templates only; the env variable provides a comma-separated list of scalar values.

**Why this priority**: Enables fully dynamic stage generation without modifying Jenkins.yml. Scoped to single-argument templates to keep env-variable parsing unambiguous and easy to set in Jenkins.

**Independent Test**: `iterated: { template: build, over: env.PLATFORMS }` with `PLATFORMS=arm64,x86_64` generates two stages at runtime in the appropriate context.

**Acceptance Scenarios**:

1. **Given** `over: env.PLATFORMS` and `PLATFORMS=arm64,x86_64` (comma-separated) inside `parallels`, **When** the pipeline runs, **Then** two stages execute in parallel—one per value—with the single declared argument bound to each.
2. **Given** the same inside `stages`, **When** the pipeline runs, **Then** two stages execute sequentially in value order.
3. **Given** the same inside `steps`, **When** the pipeline runs, **Then** the template steps are inlined twice sequentially.
4. **Given** `over: env.MATRIX` and `MATRIX` is a JSON array of objects (e.g., `[{"SUITE":"unit","TARGET":"arm64"},{"SUITE":"integration","TARGET":"x86_64"}]`) with a multi-argument template, **When** the pipeline runs, **Then** one stage is generated per JSON object with all arguments substituted.
5. **Given** `over: env.PLATFORMS` and the value is a JSON array of strings (e.g., `["arm64","x86_64"]`), **When** the pipeline runs, **Then** the result is identical to the comma-separated case.
6. **Given** `over: env.PLATFORMS` and `PLATFORMS` is not set or is empty, **When** the pipeline runs, **Then** nothing is generated and execution continues without error.
7. **Given** `over: env.PLATFORMS` and `PLATFORMS` is not resolvable as an environment variable, **When** the pipeline is loaded, **Then** a descriptive error identifies the unresolvable reference.

---

### Edge Cases

- What happens when a template name does not exist? → Descriptive load-time error naming the missing template; nothing executes.
- What happens when two iterated items in `parallels`/`stages` produce stages with the same name? → Conflict error raised before execution begins.
- What happens when `over:` is neither a scalar list, a map list, nor an `env.VAR_NAME` reference? → Load-time error describing the expected syntax.
- What happens when `iterated:` is in a `stages` block and one generated stage fails? → Normal sequential failure behavior; subsequent generated stages do not run.
- What happens when `over:` is a scalar list but the bound value contains characters invalid for a stage name? → The invalid characters are sanitized or a load-time error is raised with guidance.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Pipeline authors MUST be able to declare a named template stage in Jenkins.yml with an explicit `arguments:` list and a `steps:` section; a template MUST NOT contain nested stages, `parallels` blocks, or other template references.
- **FR-002**: `parallels`, `stages`, and `steps` blocks MUST each accept direct `template:` entries that reference a named template and supply argument bindings as sibling keys.
- **FR-003**: A direct `template:` entry in `parallels` or `stages` MUST generate one new stage with all arguments substituted; in `steps` it MUST inline the template steps into the current stage without creating a new stage.
- **FR-004**: `parallels`, `stages`, and `steps` blocks MUST each accept `iterated:` entries containing a `template:` name and an `over:` value.
- **FR-005**: When `over:` is a scalar list, the system MUST generate one stage or step block per item and bind each scalar value to the template's single declared argument; using a scalar list with a multi-argument template MUST produce a load-time error.
- **FR-006**: When `over:` is a map list, the system MUST generate one stage or step block per map entry, binding each map key to the corresponding declared argument; a map entry missing a declared argument MUST produce a load-time error.
- **FR-007**: When `over:` uses the `env.VAR_NAME` syntax, the system MUST resolve the variable at runtime and auto-detect the format: (a) if the value is valid JSON array of objects, treat as map list (multi-argument templates supported); (b) if valid JSON array of strings/primitives, treat as scalar list (single-argument only); (c) otherwise, split on comma as scalar list (single-argument only); Jenkins environment variables are always strings—this auto-detection is the only mechanism for multi-argument iteration via env.
- **FR-008**: Generated stage names for `parallels`/`stages` instantiations MUST be unique and incorporate the iterated value or a stable identifier so stages are distinguishable in pipeline output.
- **FR-009**: An empty `over:` list or unset/empty env variable MUST result in zero stages or step blocks being generated; pipeline execution MUST continue without error.
- **FR-010**: A referenced template name that does not exist MUST cause a descriptive load-time error before any stage or step executes.
- **FR-011**: A template definition containing structural elements (nested stages, parallels, or other template references) MUST be rejected at load time with a descriptive error.
- **FR-012**: Mixing explicit `template:` entries and `iterated:` entries within the same `parallels` block MUST be supported; all resulting stages run in parallel together.

### Key Entities

- **Template**: A named, steps-only stage definition with an explicit `arguments:` list and a `steps:` section; cannot contain stages, parallels, or structural elements.
- **Template Reference**: A direct `template:` entry in a `parallels`, `stages`, or `steps` block paired with argument bindings as sibling keys.
- **Iterated Block**: An `iterated:` entry in a `parallels`, `stages`, or `steps` block that generates multiple template instantiations by looping over a scalar list, a map list, or an env variable (resolved as scalar list or map list via auto-detection).
- **Variable Binding**: A key-value pair that substitutes a named argument placeholder throughout the template at instantiation time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A pipeline author can define one template and reference it any number of times across `parallels`, `stages`, and `steps` blocks—eliminating all duplicate step definitions for logic that differs only by argument values.
- **SC-002**: Adding a new value to an `over:` list (or env variable) automatically adds a new stage or step block with zero changes to template definitions or stage logic.
- **SC-003**: A Jenkins.yml using templates and iteration produces pipeline behavior identical to one with manually duplicated stages/steps for the same set of values.
- **SC-004**: All configuration errors (missing template, missing argument, unresolvable env var, invalid template structure, scalar list on multi-argument template) are caught before any stage or step executes.
- **SC-005**: A pipeline with N items in `over:` generates exactly N stages (in `parallels`/`stages`) or N inlined step blocks (in `steps`), each with correctly substituted content.

## Assumptions

- Templates are declared at the top level of Jenkins.yml alongside regular stages; the exact declaration syntax is left to the planning phase.
- Jenkins environment variables are always strings. `over: env.VAR_NAME` receives a plain string; the system auto-detects its format: JSON array of objects → map list (multi-arg supported), JSON array of strings → scalar list, otherwise → comma-split scalar list. This is the only mechanism for multi-argument env-variable iteration.
- Combining multiple separate env vars into a zipped iteration is out of scope; encode multi-argument data as a JSON array of objects in a single env var instead.
- Template stages may also be used with the existing `use` directive; this feature does not replace `use` but provides an alternative instantiation mechanism.
- The feature targets Jenkins.yml authoring; runtime resolution and execution mechanics are implementation concerns, not specification constraints.

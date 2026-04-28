# Quickstart: Stage Templates with Iteration

## What This Feature Does

Lets you define a reusable set of steps once as a template, then instantiate it multiple times with different variable values—in parallel, sequentially, or inlined within a stage.

---

## 1. Define a Template

Add a top-level stage with a `template:` key and declare its arguments:

```yaml
# Jenkins.yml

compile_target:
  template:
    arguments: [TARGET]
  steps:
    - sh: make clean TARGET={TARGET}
    - sh: make build TARGET={TARGET}
```

Templates can have multiple arguments:

```yaml
run_test:
  template:
    arguments: [SUITE, TARGET]
  steps:
    - sh: pytest tests/{SUITE} --target {TARGET}
    - sh: grep "PASSED" results/{SUITE}/report.txt
```

---

## 2. Use a Template Directly

Reference a template in `parallels`, `stages`, or `steps` with its argument values:

```yaml
# In parallels — generates two parallel stages
build_all:
  parallels:
    - template: compile_target
      TARGET: arm64
    - template: compile_target
      TARGET: x86_64

# In stages — generates two sequential stages
build_sequential:
  stages:
    - template: compile_target
      TARGET: arm64
    - template: compile_target
      TARGET: x86_64

# In steps — inlines the template steps (no new stage created)
build_and_test:
  steps:
    - sh: echo "starting build"
    - template: compile_target
      TARGET: arm64
    - sh: echo "build done"
```

---

## 3. Iterate over a Static List

Use `iterated:` to generate one stage (or step block) per item automatically:

```yaml
# Single-argument template + scalar list
build_platforms:
  parallels:
    - iterated:
        template: compile_target
        over: [arm64, x86_64, riscv]
# Generates: compile_target_arm64, compile_target_x86_64, compile_target_riscv (in parallel)

# Multi-argument template + map list
test_matrix:
  parallels:
    - iterated:
        template: run_test
        over:
          - SUITE: unit
            TARGET: arm64
            _name: unit_arm64
          - SUITE: integration
            TARGET: x86_64
            _name: integration_x86_64
# Generates: run_test_unit_arm64, run_test_integration_x86_64 (in parallel)

# Sequential iteration
build_in_order:
  stages:
    - iterated:
        template: compile_target
        over: [arm64, x86_64]
# Generates: compile_target_arm64, then compile_target_x86_64 (sequential)

# Inline iteration inside steps
deploy_all:
  steps:
    - sh: echo "deploying to all targets"
    - iterated:
        template: compile_target
        over: [arm64, x86_64]
    - sh: echo "all deploys done"
# Inlines compile_target steps twice, no new stages
```

---

## 4. Iterate over a Jenkins Environment Variable

Jenkins env vars are always strings, but the value is auto-detected at runtime:

```yaml
# Comma-separated string → scalar list (single-arg template)
# Jenkins parameter: PLATFORMS = arm64,x86_64
build_from_env:
  parallels:
    - iterated:
        template: compile_target    # arguments: [TARGET]
        over: env.PLATFORMS
# Generates: compile_target_arm64, compile_target_x86_64
```

```yaml
# JSON array of strings → scalar list (single-arg template)
# Jenkins parameter: PLATFORMS = ["arm64","x86_64"]
build_from_env_json:
  parallels:
    - iterated:
        template: compile_target    # arguments: [TARGET]
        over: env.PLATFORMS
# Same result: compile_target_arm64, compile_target_x86_64
```

```yaml
# JSON array of objects → map list (multi-arg template)
# Jenkins parameter: MATRIX = [{"TARGET":"arm64","MODE":"debug"},{"TARGET":"x86_64","MODE":"release"}]
test_matrix_from_env:
  parallels:
    - iterated:
        template: run_test          # arguments: [TARGET, MODE]
        over: env.MATRIX
# Generates: run_test_0, run_test_1 (or use _name key in each map for readable names)
```

**Auto-detection order**:
1. Try JSON parse — if valid JSON array of objects → map list; if valid JSON array of strings → scalar list
2. JSON fails → comma-split → scalar list

---

## 5. Mix Templates with Regular Entries

Template references and existing stage entries can be combined freely in the same block:

```yaml
full_build:
  parallels:
    - setup_stage               # existing string entry
    - template: compile_target  # direct template reference
      TARGET: arm64
    - iterated:                 # iterated template reference
        template: compile_target
        over: [x86_64, riscv]
```

---

## Rules to Remember

| Rule | Detail |
|------|--------|
| Templates are steps-only | No `stages:` or `parallels:` inside a template |
| Scalar iteration = single argument | `over: [a, b, c]` or `over: env.VAR` requires exactly one declared argument |
| Map iteration = any arguments | Each map entry must supply all declared arguments |
| `_name:` is reserved | Use it in map entries to control the generated stage name |
| Empty `over:` is not an error | Zero items → zero stages generated; pipeline continues |
| Errors are caught early | Missing templates, missing args, wrong argument count: all fail before any stage runs |

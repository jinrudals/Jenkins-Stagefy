# Quickstart: `use` Step Directive

**Branch**: `001-steps-use-directive` | **Date**: 2026-04-28

## 기본 사용 패턴

`use` 디렉티브는 `steps` 블록 내에서 외부 YAML 파일의 스테이지를 참조한다.
파일은 `use` 스텝이 실행되는 시점에 존재하면 되므로, 바로 앞 `sh` 스텝에서 shell heredoc으로 직접 생성하는 방식이 의존성 없이 가장 간단하다.

---

### 예시 1: shell heredoc으로 YAML 생성 후 스테이지 실행 (makeStage: true, 기본)

```yaml
# Jenkins.yml
build:
  steps:
    - sh: |
        cat << 'EOF' > tasks.yaml
        build_all:
          parallels:
            - build_a
            - build_b
        build_a:
          steps:
            - sh: echo building A && mkdir -p out/a
        build_b:
          steps:
            - sh: echo building B && mkdir -p out/b
        EOF
    - use: build_all from tasks.yaml
    - sh: echo "BUILD DONE"
```

**Jenkins UI 결과**:
```
[build]
  └─ sh: cat << 'EOF' > tasks.yaml ...
  └─ [build_all]         ← use로 생성된 스테이지
       ├─ [build_a]
       └─ [build_b]
```

---

### 예시 2: 인라인 실행 (makeStage: false)

환경 초기화처럼 현재 스테이지의 일부로 실행해야 할 때.

```yaml
# Jenkins.yml
setup:
  steps:
    - sh: |
        cat << 'EOF' > init.yaml
        init_env:
          steps:
            - sh: mkdir -p workspace/out
            - sh: echo ready > workspace/status.txt
        EOF
    - use: init_env from init.yaml
      makeStage: false
    - sh: cat workspace/status.txt
```

**Jenkins UI 결과**:
```
[setup]                  ← 단일 스테이지 (init_env 스테이지 없음)
  └─ sh: cat << 'EOF' > init.yaml ...
  └─ (init_env steps 인라인 실행)
  └─ sh: cat workspace/status.txt
```

---

### 예시 3: DAG 구조 (동일 스테이지 다중 참조 — 정상)

```yaml
# Jenkins.yml
main:
  steps:
    - sh: |
        cat << 'EOF' > shared.yaml
        cleanup:
          steps:
            - sh: rm -rf tmp && mkdir tmp
        full_job:
          steps:
            - use: cleanup from shared.yaml
            - sh: echo all done
        EOF
    - use: cleanup from shared.yaml
    - use: full_job from shared.yaml   # full_job도 cleanup 참조 — DAG, 순환 아님 → OK
```

---

## 오류 시나리오

| 상황                       | 오류 메시지 예시                                                          |
| -------------------------- | ------------------------------------------------------------------------- |
| `" from "` 없는 use 값     | `use directive must follow 'StageName from filepath' format: 'bad_value'` |
| heredoc 오류로 파일 미생성 | `readYaml` Jenkins 내장 오류 (파일 없음)                                  |
| 스테이지명 미존재          | `No matching type`                                                        |
| 순환 참조 (`A → B → A`)    | `Circular Loop Execution B from shared.yaml`                              |

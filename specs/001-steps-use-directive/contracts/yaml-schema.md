# YAML Schema Contract: `use` Step Directive

**Branch**: `001-steps-use-directive` | **Date**: 2026-04-28

## steps 블록 스키마 (확장)

`steps` 타입 스테이지의 `steps` 배열은 아래 타입 중 하나를 각 항목으로 가진다.

### 기존 타입 (변경 없음)

```yaml
# sh
- sh: "<shell command>"

# script  
- script: "<groovy script path>"

# setEnvFromFile
- setEnvFromFile: "<yaml filepath>"

# evaluate
- evaluate: "<groovy expression>"
```

### 신규 타입: use

```yaml
# use (makeStage 기본값 true)
- use: "<StageName> from <filepath>"

# use (makeStage 명시)
- use: "<StageName> from <filepath>"
  makeStage: true   # Jenkins UI에 스테이지 생성

- use: "<StageName> from <filepath>"
  makeStage: false  # 인라인 실행, 스테이지 미생성
```

## 전체 steps 스테이지 예시

```yaml
fetch_and_run:
  env:
    BUILD_TYPE: release
  node: linux-agent
  steps:
    - sh: wget http://ci-server/shared/common.yaml -o common.yaml
    - use: build_common from common.yaml       # makeStage: true (기본)
    - sh: echo "build complete"

fetch_and_inline:
  steps:
    - sh: wget http://ci-server/shared/setup.yaml -o setup.yaml
    - use: env_setup from setup.yaml
      makeStage: false                          # 현재 스테이지에 인라인
    - sh: echo "env ready"
```

## 변경 없는 기존 문법 (하위 호환)

```yaml
# stages/parallels 내 from 문법 — 변경 없음
my_pipeline:
  stages:
    - stage_a
    - stage_b from other.yaml     # 기존 문법 그대로 유지

my_parallel:
  parallels:
    - task_x
    - task_y from other.yaml      # 기존 문법 그대로 유지
```

## 제약 조건

| 제약 | 상세 |
|------|------|
| `use` 허용 위치 | `steps` 타입 블록의 `steps` 배열 내부만 허용 |
| `stages`/`parallels` 내 `use` | 미지원 (기존 `from` 문법 사용) |
| `filepath` 기준 | `$WORKSPACE` 기준 상대 경로 |
| `StageName from filepath` 형식 | ` from `(공백 포함) 구분자 필수 |
| `makeStage` 타입 | Boolean (`true`/`false`). 생략 시 `true` |

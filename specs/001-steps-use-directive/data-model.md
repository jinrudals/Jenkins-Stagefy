# Data Model: `use` Step Directive

**Branch**: `001-steps-use-directive` | **Date**: 2026-04-28

## 기존 스텝 타입 (변경 없음)

`steps` 배열의 각 항목은 아래 중 하나의 키를 가진다:

| 키 | 값 타입 | 설명 |
|----|---------|------|
| `sh` | String | shell 명령 실행 |
| `script` | String | Groovy 스크립트 파일 실행 |
| `setEnvFromFile` | String | YAML 파일에서 환경변수 로드 |
| `evaluate` | String | Groovy 표현식 동적 평가 |

## 신규 스텝 타입: `use`

```yaml
- use: "<StageName> from <filepath>"
  makeStage: <boolean>   # optional, default: true
```

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `use` | String | 필수 | — | `"StageName from filepath"` 형식. `StageName`은 참조할 스테이지명, `filepath`는 workspace root 기준 상대 경로 |
| `makeStage` | Boolean | 선택 | `true` | `true`: Jenkins UI에 새 스테이지 생성 후 실행. `false`: 현재 스테이지 컨텍스트에 인라인 실행 |

### `use` 값 파싱 규칙

```
"<StageName> from <filepath>"
  └─ split(" from ", 2)
       ├─ parts[0].trim() → StageName
       └─ parts[1].trim() → filepath
```

- `" from "`(공백 포함)을 구분자로 사용 → 스테이지명에 `from`이 포함된 경우 오파싱 방지
- `filepath`는 `$WORKSPACE` 기준 상대 경로 (절대 경로도 허용)
- `" from "` 미포함 시: 오류 발생

### 유효성 검증 규칙

| 조건 | 동작 |
|------|------|
| `" from "` 패턴 없음 | `error()` 로 빌드 실패 |
| `filepath` 파일 미존재 | `readYaml` 내장 오류 발생 |
| `StageName` 스테이지 미존재 | `run()` → `error('No matching type')` |
| 순환 참조 감지 | `check_circular_loop` 예외 발생 |

## 실행 상태 모델

```
use 디렉티브 처리 시작
       │
       ▼
parse("StageName from filepath")
       │
       ├── 파싱 실패 → error()
       │
       ▼
childStage = new Stagefy(filepath, StageName, thisStage)
       │
       ▼
childStage.check_circular_loop(childStage)
       │
       ├── 순환 감지 → Exception 발생
       │
       ▼
makeStage == true?
       ├── YES → stage(StageName) { childStage.run() }
       └── NO  → childStage.run()
                     │
                     ▼
              run() 내부에서 when 평가 → steps/stages/parallels 분기
```

## Stagefy 클래스 parent 체인 (순환 참조 감지용)

```
[grandparent: A] ← parent
      │
      ▼
[parent: B] ← this (현재 steps_run 실행 중인 스테이지)
      │
      ▼ (use 디렉티브)
[child: C] ← parent = B

check_circular_loop(C):
  C.parent = B → B != C → continue
  B.parent = A → A != C → continue
  A.parent = null → return true (OK)
```

# Pipeline Contract: stagefy

**Type**: Jenkins GlobalVariable (Declarative & Scripted Pipeline 호환)

## 접근 방식

플러그인 설치 시 `stagefy` GlobalVariable이 자동 등록된다. `@Library` 선언 없이 파이프라인에서 바로 사용 가능.

## API

```groovy
stagefy.run(String file, String stage)
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | String | Yes | YAML 설정 파일 경로 (워크스페이스 상대 경로) |
| `stage` | String | Yes | 실행할 최상위 스테이지 이름 |

## Return Value

없음 (void). 실패 시 `AbortException`으로 파이프라인 중단.

## Usage Examples

### Declarative Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    stagefy.run('pipeline.yml', 'build')
                }
            }
        }
    }
}
```

### Scripted Pipeline

```groovy
node {
    stagefy.run('pipeline.yml', 'build')
}
```

## Error Behavior

| Condition | Behavior |
|-----------|----------|
| YAML 파일 없음 | `AbortException` |
| 스테이지 없음 | `AbortException`: "Stage '{name}' not found in '{file}'" |
| YAML 파싱 오류 | `AbortException` |
| 순환 참조 감지 | `RuntimeException`: "Circular Loop Execution {stage} from {file}" |
| 템플릿 인자 누락 | `AbortException`: "Template '{name}' requires argument '{arg}'..." |

## YAML Format Compatibility

기존 Shared Library와 동일한 YAML 형식을 지원한다. 변경 없음.

## 마이그레이션

기존 Jenkinsfile에서 `@Library('stagefy') _` 선언만 제거하면 된다. `stagefy.run(file, stage)` 호출은 동일.

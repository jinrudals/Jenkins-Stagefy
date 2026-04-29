# Stagefy

YAML 기반 Jenkins 파이프라인 스테이지 로더. Jenkinsfile의 반복적인 stage/steps 보일러플레이트를 YAML 선언으로 대체한다.

## 구조

```
vars/stagefy.groovy   # Jenkins Shared Library (레거시, standalone)
plugin/               # Jenkins Plugin (hpi) — 권장
examples/             # Jenkinsfile 및 YAML 예제
```

## 핵심 기능

- **YAML 기반 스테이지 정의** — `sh`, `script`, `evaluate`, `setEnvFromFile` 등의 스텝을 YAML로 선언
- **OOP 아키텍처** — StepsStage / SequentialStage / ParallelStage 계층 구조
- **템플릿 시스템** — `template` + `arguments`로 재사용 가능한 스텝 세트 정의, `iterated`로 반복 실행
- **병렬 실행** — `parallels` 키로 병렬 스테이지 선언, iterated 템플릿과 조합 가능
- **use 디렉티브** — 외부 YAML 파일의 스테이지를 참조하여 DAG 구성
- **환경 변수 치환** — `${env.VAR}` 구문으로 런타임 변수 주입

## 빠른 시작

### Plugin 방식 (권장)

```groovy
// Jenkinsfile
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    stagefy.run('Jenkins.yml', 'build')
                }
            }
        }
    }
}
```

```yaml
# Jenkins.yml
build:
  steps:
    - sh: "make build"
    - sh: "make test"
```

### Shared Library 방식 (레거시)

Jenkins 관리 > Global Pipeline Libraries에 이 저장소를 등록한 뒤:

```groovy
@Library('stagefy') _
stagefy('Jenkins.yml', 'build')
```

## YAML 스키마 예시

```yaml
# 환경 변수
env:
  APP_NAME: myapp

# 순차 스테이지
build:
  steps:
    - sh: "echo building ${env.APP_NAME}"

# 병렬 스테이지
test:
  parallels:
    - unit_test
    - integration_test

# 템플릿 + 반복
compile_target:
  template:
    arguments: [TARGET]
  steps:
    - sh: "make build TARGET={TARGET}"

compile_all:
  parallels:
    - iterated:
        template: compile_target
        over: env.COMPILE_TARGETS
```

## Plugin 빌드 및 설치

```bash
cd plugin
mvn clean package -DskipTests
# target/stagefy.hpi 생성
```

자세한 내용은 [plugin/README.md](plugin/README.md) 참조.

## 요구 사항

| 항목 | 버전 |
|------|------|
| Jenkins | 2.440.3+ |
| Java | 21 |
| 필수 플러그인 | workflow-aggregator, pipeline-utility-steps |

## 문서

- [Plugin README](plugin/README.md)
- [설치 가이드](plugin/install.md)
- [배포 가이드](plugin/deploy.md)

## 라이선스

MIT

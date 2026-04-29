# Stagefy Jenkins Plugin

YAML 기반 파이프라인 스테이지 로더를 Jenkins 플러그인(hpi)으로 패키징한 것. `stagefy` 글로벌 변수를 제공하여 Jenkinsfile에서 바로 사용할 수 있다.

## 아키텍처

```
StagefyGlobalVariable (Java, @Extension)
  └─ StagefyDsl.groovy          # stagefy.run(file, stage) 진입점
      └─ DslJenkinsContext       # Jenkins DSL 래퍼
      └─ core/
          ├─ StageFactory        # YAML → Stage 객체 변환
          ├─ StepsStage          # 순차 스텝 실행
          ├─ SequentialStage     # 순차 스테이지 (stages 키)
          ├─ ParallelStage       # 병렬 스테이지 (parallels 키)
          └─ StepFactory         # YAML → Step 객체 변환
              ├─ ShStep
              ├─ ScriptStep
              ├─ EvaluateStep
              ├─ SetEnvFromFileStep
              └─ UseStageStep    # 외부 YAML 참조 (use 디렉티브)
      └─ util/
          ├─ EnvResolver         # ${env.VAR} 치환
          ├─ TemplateResolver    # template + arguments 처리
          ├─ IterationResolver   # iterated 반복 처리
          ├─ ModuleResolver      # use 디렉티브 해석
          └─ StageNameBuilder    # 스테이지 이름 생성
```

## 빌드

```bash
mvn clean package -DskipTests
# target/stagefy.hpi 생성
```

또는 Makefile 사용:

```bash
make build          # mvn clean package -DskipTests
make test           # mvn test
make clean          # mvn clean
```

## 사용법

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

## 요구 사항

| 항목 | 버전 |
|------|------|
| Jenkins | 2.440.3+ |
| Java | 21 |
| Maven | 3.8+ |
| 필수 플러그인 | workflow-aggregator, pipeline-utility-steps |

## Docker 테스트

플러그인이 번들된 Jenkins 이미지를 빌드하여 로컬 테스트:

```bash
make docker-build   # Docker 이미지 빌드
make docker-run     # localhost:8080 에서 Jenkins 실행
```

## 관련 문서

- [설치 가이드](install.md)
- [배포 가이드](deploy.md)

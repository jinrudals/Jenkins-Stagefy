# Implementation Plan: Jenkins Plugin으로 전환

**Branch**: `004-jenkins-plugin-migration` | **Date**: 2026-04-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-jenkins-plugin-migration/spec.md`

## Summary

Stagefy Shared Groovy Library를 Jenkins Plugin(HPI)으로 전환하여 Script Security 승인 부담을 제거한다. **GlobalVariable + CPS Groovy 리소스** 아키텍처를 사용하여, Java는 플러그인 등록만 담당하고 실제 파이프라인 DSL 호출(stage/parallel/node)은 CPS 컨텍스트 내 Groovy 리소스에서 수행한다.

## Technical Context

**Language/Version**: Groovy + Java, OpenJDK 21 (`/tools/java/openjdk/21/bin/java`)
**Primary Dependencies**: jenkins-plugin-parent-pom 4.80, workflow-step-api, workflow-cps, pipeline-utility-steps, snakeyaml 2.2
**Storage**: N/A (파일 기반 YAML 설정만 사용)
**Testing**: JenkinsRule (jenkins-test-harness), Maven Surefire
**Target Platform**: Jenkins LTS 2.440.3+
**Project Type**: Jenkins Plugin (HPI)
**Performance Goals**: 기존 Shared Library 대비 실행 시간 차이 10% 이내
**Constraints**: `stage()` DSL은 CPS Groovy 컨텍스트에서만 동작 — Java StepExecution에서 동적 stage 생성 불가
**Scale/Scope**: 사내 Jenkins 인스턴스 배포, 기존 YAML 파이프라인 100% 호환

## Constitution Check

*GATE: Constitution이 프로젝트별로 설정되지 않은 템플릿 상태이므로 위반 사항 없음. 통과.*

## Architecture Decision: GlobalVariable vs AbstractStepExecution

### 문제

이전 research(R1~R3)에서는 `AbstractSynchronousNonBlockingStepExecution` 기반 접근을 계획했으나, 핵심 제약을 발견:

- **Java `StepExecution` 내부에서 `stage()`, `parallel()`, `node()` 등 Pipeline DSL을 호출할 수 없다.**
- 이 DSL들은 CPS 변환된 Groovy 실행 컨텍스트에서만 동작한다.
- Stagefy의 핵심 기능인 **동적 stage 생성**이 Java Step에서는 불가능하다.

### 결정: GlobalVariable + Groovy 리소스 방식

```
┌─────────────────────────────────────────────────────────┐
│ Java (@Extension)                                       │
│  StagefyGlobalVariable.java                             │
│   ├─ getName() → "stagefy"                              │
│   ├─ getValue() → StagefyDsl 인스턴스 생성               │
│   └─ Allowlist (inner class)                            │
│       └─ isAllowed() → *.groovy 리소스 허용              │
└──────────────────────┬──────────────────────────────────┘
                       │ CpsScript 전달
┌──────────────────────▼──────────────────────────────────┐
│ Groovy Resources (CPS 컨텍스트 내 실행)                   │
│  StagefyDsl.groovy                                      │
│   └─ run(file, stage) → DslJenkinsContext 생성           │
│                          → StageFactory.create()         │
│                          → script.stage() { obj.run() }  │
│                                                          │
│  DslJenkinsContext.groovy                                │
│   └─ CpsScript 래핑: stage(), parallel(), sh(), etc.     │
│                                                          │
│  core/ (Stage/Step 계층)                                 │
│   ├─ Stage.groovy, StageFactory.groovy                   │
│   ├─ StepsStage, SequentialStage, ParallelStage          │
│   ├─ Step.groovy, StepFactory.groovy                     │
│   └─ ShStep, ScriptStep, UseStageStep, etc.              │
│                                                          │
│  util/ (유틸리티)                                         │
│   ├─ EnvResolver, ModuleResolver, StageNameBuilder       │
│   ├─ TemplateResolver, IterationResolver, Template       │
└──────────────────────────────────────────────────────────┘
```

### 이 방식의 장점

1. **CPS 컨텍스트 유지**: Groovy 리소스가 `CpsScript`를 통해 `stage()`, `parallel()` 등을 직접 호출 가능
2. **Sandbox 우회**: `GroovySourceFileAllowlist`로 플러그인 내 Groovy 파일을 허용 → Script Security 승인 불필요
3. **기존 코드 최대 재사용**: Shared Library의 OOP 구조를 거의 그대로 이전 가능
4. **호출 방식 호환**: `stagefy.run(file, stage)` 시그니처 유지

### 이전 research와의 차이점

| 항목 | 이전 Research | 현재 결정 |
|------|--------------|-----------|
| 진입점 | `AbstractSynchronousNonBlockingStepExecution` | `GlobalVariable` + `StagefyDsl.groovy` |
| DSL 접근 | `StepContext.get()` → 불가 판명 | `CpsScript` 직접 래핑 (`DslJenkinsContext`) |
| JenkinsContext | `StepContext` 기반 재구현 | `CpsScript` 래핑 (기존 구조 유지) |
| `@NonCPS` | 제거 | 제거 (동일) |
| 호출 방식 | `stagefy file: 'x', stage: 'y'` | `stagefy.run('x', 'y')` |

## Project Structure

### Documentation (this feature)

```text
specs/004-jenkins-plugin-migration/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output (업데이트 필요)
├── data-model.md        # Phase 1 output (업데이트 필요)
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── pipeline-step.md # Pipeline Step contract
└── tasks.md             # Phase 2 output
```

### Source Code

```text
plugin/
├── pom.xml                                          # Maven 빌드 (jenkins-plugin-parent 4.80)
├── Dockerfile                                       # 로컬 테스트용 Jenkins Docker
├── Jenkinsfile                                      # 플러그인 자체 CI
├── plugins.txt                                      # Docker 의존 플러그인 목록
├── test-pipeline.yml                                # 테스트용 YAML
├── src/
│   ├── main/
│   │   ├── java/io/jenkins/plugins/stagefy/
│   │   │   └── StagefyGlobalVariable.java           # @Extension: GlobalVariable + Allowlist
│   │   └── resources/io/jenkins/plugins/stagefy/
│   │       ├── StagefyDsl.groovy                    # 진입점: run(file, stage)
│   │       ├── DslJenkinsContext.groovy              # CpsScript 래핑 어댑터
│   │       ├── core/
│   │       │   ├── Stage.groovy                     # 추상 Stage 기반 클래스
│   │       │   ├── StageFactory.groovy              # Stage 타입 결정
│   │       │   ├── StepsStage.groovy                # steps 블록 실행
│   │       │   ├── SequentialStage.groovy           # stages 순차 실행
│   │       │   ├── ParallelStage.groovy             # parallels 병렬 실행
│   │       │   ├── Step.groovy                      # 추상 Step 기반 클래스
│   │       │   ├── StepFactory.groovy               # Step 타입 결정
│   │       │   ├── ShStep.groovy                    # sh 명령어
│   │       │   ├── ScriptStep.groovy                # Groovy 스크립트 로드
│   │       │   ├── EvaluateStep.groovy              # Groovy 표현식 평가
│   │       │   ├── SetEnvFromFileStep.groovy        # YAML에서 환경 변수 로드
│   │       │   └── UseStageStep.groovy              # 다른 스테이지 참조 실행
│   │       └── util/
│   │           ├── EnvResolver.groovy               # ${env.VAR} 치환
│   │           ├── ModuleResolver.groovy            # module load 접두사
│   │           ├── StageNameBuilder.groovy          # 스테이지 이름 생성
│   │           ├── TemplateResolver.groovy          # {ARG} 바인딩 치환
│   │           ├── IterationResolver.groovy         # iterated over 확장
│   │           └── Template.groovy                  # 템플릿 검증/데이터
│   └── test/
│       ├── java/io/jenkins/plugins/stagefy/        # JenkinsRule 통합 테스트
│       └── resources/                               # 테스트용 YAML 파일

vars/
└── stagefy.groovy                                   # 기존 Shared Library (병행 유지)
```

**Structure Decision**: 기존 `plugin/` 디렉토리 구조를 유지. Java는 `src/main/java/`에 `StagefyGlobalVariable.java` 1개만, 나머지 모든 로직은 `src/main/resources/` 하위 Groovy 리소스로 배치.

## Current Implementation Status

### 완료된 부분

1. **Java 진입점**: `StagefyGlobalVariable.java` — `@Extension`, `GlobalVariable`, `GroovySourceFileAllowlist` 구현 완료
2. **Groovy DSL 진입점**: `StagefyDsl.groovy` — `run(file, stage)` 메서드 구현 완료
3. **Jenkins 어댑터**: `DslJenkinsContext.groovy` — `CpsScript` 래핑, `java.util.logging` 전환 완료
4. **Stage 계층**: `Stage`, `StepsStage`, `SequentialStage`, `ParallelStage`, `StageFactory` — 이전 완료
5. **Step 계층**: `Step`, `ShStep`, `ScriptStep`, `EvaluateStep`, `SetEnvFromFileStep`, `UseStageStep`, `StepFactory` — 이전 완료
6. **유틸리티**: `EnvResolver`, `ModuleResolver`, `StageNameBuilder`, `TemplateResolver`, `IterationResolver`, `Template` — 이전 완료
7. **빌드 설정**: `pom.xml`, `Dockerfile`, `plugins.txt` — 구성 완료

### 발견된 버그 (수정 필요)

1. **잘못된 패키지 선언**: `ShStep.groovy`, `ScriptStep.groovy`, `EvaluateStep.groovy`, `SetEnvFromFileStep.groovy`, `UseStageStep.groovy`, `SequentialStage.groovy`, `ParallelStage.groovy`에서 패키지가 `io.getJenkins().plugins.stagefy.core`로 잘못 선언됨 → `io.jenkins.plugins.stagefy.core`로 수정 필요
2. **생성자 파라미터 이름 오류**: `ShStep`, `ScriptStep`, `EvaluateStep`, `SetEnvFromFileStep`, `UseStageStep`의 생성자에서 `String getModuleprefix()`로 잘못 선언됨 → `String moduleprefix`로 수정 필요
3. **import 경로 오류**: `SequentialStage.groovy`, `ParallelStage.groovy`에서 `io.getJenkins().plugins.stagefy.util.*`로 잘못 import됨 → `io.jenkins.plugins.stagefy.util.*`로 수정 필요
4. **`SequentialStage`/`ParallelStage`에서 `jenkins` 직접 참조**: `tpl.validate(jenkins)` 등에서 private 필드 `jenkins`를 직접 참조 → `getJenkins()`로 수정 필요

### 미완료 부분

1. **테스트**: JenkinsRule 기반 통합 테스트 미작성
2. **빌드 검증**: Maven 빌드 및 HPI 생성 미검증
3. **E2E 검증**: Docker 기반 실제 Jenkins에서 파이프라인 실행 미검증
4. **research.md / data-model.md 업데이트**: 이전 문서가 `AbstractStepExecution` 기반으로 작성되어 현재 `GlobalVariable` 방식과 불일치

## Implementation Phases

### Phase 1: 버그 수정 및 빌드 검증

1. 패키지 선언, 생성자 파라미터, import 경로 오류 수정 (7개 파일)
2. `jenkins` → `getJenkins()` 참조 수정
3. Maven 빌드 (`mvn package`) 성공 확인
4. HPI 파일 생성 확인

### Phase 2: 통합 테스트

1. JenkinsRule 기반 테스트 클래스 작성
   - 기본 `stagefy.run()` 호출 테스트
   - StepsStage (sh 스텝) 실행 테스트
   - SequentialStage (순차 스테이지) 테스트
   - ParallelStage (병렬 스테이지) 테스트
   - 환경 변수 치환 테스트
   - 에러 케이스 (파일 없음, 스테이지 없음, 순환 참조) 테스트
2. 테스트용 YAML 리소스 파일 작성
3. `mvn test` 통과 확인

### Phase 3: E2E 검증 및 문서 업데이트

1. Docker 기반 Jenkins에서 실제 파이프라인 실행 검증
2. 기존 예제 Jenkinsfile 호환성 확인
3. research.md, data-model.md를 현재 GlobalVariable 방식에 맞게 업데이트
4. quickstart.md 업데이트 (호출 방식: `stagefy.run()`)

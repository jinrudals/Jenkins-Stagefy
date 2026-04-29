# 설치 가이드

## 사전 요구 사항

| 항목 | 버전 | 비고 |
|------|------|------|
| Jenkins | 2.440.3+ | LTS 권장 |
| Java | 21 | Jenkins 실행 환경 |
| Maven | 3.8+ | 빌드 시에만 필요 |

### 필수 Jenkins 플러그인

Jenkins에 아래 플러그인이 설치되어 있어야 한다:

- `workflow-aggregator` (Pipeline)
- `pipeline-utility-steps` (readYaml 등)
- `pipeline-model-definition` (Declarative Pipeline)

## 설치 방법

### 1. hpi 파일 빌드

```bash
cd plugin
mvn clean package -DskipTests
ls target/stagefy.hpi
```

### 2. Jenkins에 설치

#### 방법 A: 웹 UI

1. Jenkins 관리 > Plugins > Advanced settings
2. **Deploy Plugin** 섹션에서 `target/stagefy.hpi` 업로드
3. Jenkins 재시작

#### 방법 B: CLI

```bash
java -jar jenkins-cli.jar -s http://JENKINS_URL/ install-plugin target/stagefy.hpi -restart
```

#### 방법 C: 파일 직접 복사

```bash
cp target/stagefy.hpi $JENKINS_HOME/plugins/stagefy.hpi
# Jenkins 재시작 필요
```

### 3. Docker 환경

Dockerfile로 플러그인이 포함된 Jenkins 이미지를 빌드:

```bash
cd plugin
docker build -t stagefy-jenkins .
docker run -p 8080:8080 stagefy-jenkins
```

`Dockerfile` 내용:

```dockerfile
FROM jenkins/jenkins:lts
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY target/stagefy.hpi /usr/share/jenkins/ref/plugins/stagefy.hpi
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
```

## 설치 확인

Jenkins에 접속하여 Pipeline 잡을 생성하고 아래 스크립트를 실행:

```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                writeFile file: 'test.yml', text: 'hello:\n  steps:\n    - sh: "echo ok"'
                script {
                    stagefy.run('test.yml', 'hello')
                }
            }
        }
    }
}
```

콘솔 출력에 `ok`가 표시되면 설치 완료.

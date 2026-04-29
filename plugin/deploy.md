# 배포 가이드

## 빌드

```bash
cd plugin
mvn clean package -DskipTests
```

빌드 결과물: `target/stagefy.hpi`

## 배포 방법

### 1. Jenkinsfile (CI/CD)

`plugin/Jenkinsfile`이 빌드 및 테스트 파이프라인을 정의하고 있다. Jenkins 잡에서 이 파이프라인을 실행하면 자동으로 hpi가 생성된다.

### 2. hpi 수동 업데이트

운영 Jenkins에 플러그인을 수동으로 업데이트하는 절차.

#### 사전 준비

- Jenkins 관리자 권한
- 빌드된 `stagefy.hpi` 파일
- Jenkins 재시작 가능한 유지보수 시간 확보

#### 절차

1. **hpi 빌드**

   ```bash
   cd plugin
   mvn clean package -DskipTests
   ```

2. **기존 플러그인 백업**

   ```bash
   cp $JENKINS_HOME/plugins/stagefy.hpi $JENKINS_HOME/plugins/stagefy.hpi.bak
   cp -r $JENKINS_HOME/plugins/stagefy $JENKINS_HOME/plugins/stagefy.bak
   ```

3. **hpi 교체**

   ```bash
   cp target/stagefy.hpi $JENKINS_HOME/plugins/stagefy.hpi
   # 언팩된 디렉토리가 있으면 삭제 (Jenkins가 재시작 시 다시 언팩)
   rm -rf $JENKINS_HOME/plugins/stagefy
   ```

4. **Jenkins 재시작**

   ```bash
   # Safe Restart (실행 중인 빌드 완료 후 재시작)
   java -jar jenkins-cli.jar -s http://JENKINS_URL/ safe-restart

   # 또는 웹 UI: http://JENKINS_URL/safeRestart
   ```

5. **버전 확인**

   Jenkins 관리 > Plugins > Installed plugins에서 `Stagefy` 버전이 업데이트되었는지 확인.

#### 롤백

문제 발생 시 백업에서 복원:

```bash
cp $JENKINS_HOME/plugins/stagefy.hpi.bak $JENKINS_HOME/plugins/stagefy.hpi
rm -rf $JENKINS_HOME/plugins/stagefy
# Jenkins 재시작
```

### 3. Docker 배포

Docker 이미지에 hpi를 포함하여 배포:

```bash
cd plugin
mvn clean package -DskipTests
docker build -t stagefy-jenkins .
```

기존 컨테이너 교체:

```bash
docker stop jenkins
docker rm jenkins
docker run -d --name jenkins -p 8080:8080 \
  -v jenkins_home:/var/jenkins_home \
  stagefy-jenkins
```

## 주의 사항

- hpi 교체 후 반드시 Jenkins를 재시작해야 새 버전이 로드된다.
- `$JENKINS_HOME/plugins/stagefy/` 디렉토리(언팩된 플러그인)를 삭제하지 않으면 이전 버전이 계속 사용될 수 있다.
- Safe Restart를 사용하면 실행 중인 빌드가 완료된 후 재시작된다.
- 운영 환경에서는 유지보수 시간에 업데이트를 진행하는 것을 권장한다.

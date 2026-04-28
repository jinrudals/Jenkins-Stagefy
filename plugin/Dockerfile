FROM jenkins/jenkins:lts
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY target/stagefy.hpi /usr/share/jenkins/ref/plugins/stagefy.hpi
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

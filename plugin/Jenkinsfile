pipeline {
    agent any
    stages {
        stage('Setup') {
            steps {
                writeFile file: 'test-pipeline.yml', text: """\
build:
  steps:
    - sh: "echo 'Hello from Stagefy Plugin!'"
    - sh: "echo 'YAML-based stage execution works!'"
"""
            }
        }
        stage('Test Stagefy Plugin') {
            steps {
                script {
                    stagefy.run('test-pipeline.yml', 'build')
                }
            }
        }
    }
}

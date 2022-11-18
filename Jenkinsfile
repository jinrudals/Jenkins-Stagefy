pipeline{
  agent any

  stages{
    stage("Prepare Workspace"){
      steps {
        script{
          cleanWs()
          checkout()
          env.prefix = "."
          // if not set as library
          stagefy = load("${env.prefix}/vars/stagefy.groovy")

          (tmp, prepareStage) = stagefy.getData("prepare", "${env.prefix}/jenkins/prepare.yaml")
          prepareStage()
        }
      }
    }

    stage("Setup Environment Variable"){
      steps{
        script{
          env.prefix = "."
          data = readYaml(file : "${env.prefix}/jenkins/environment.yaml")
          if (data != null){
            for(each in data.keySet()){
              env[each] = data[each]
            }
          }
        }
      }
    }
    stage("run"){
      steps{
        script{
          // if not set as library
          stagefy = load("${env.prefix}/vars/stagefy.groovy")

          (tmp, stageInfo) = stagefy.getData("start", "${env.prefix}/jenkins/stages.yaml")
          stageInfo()
        }
      }
    }
  }
}
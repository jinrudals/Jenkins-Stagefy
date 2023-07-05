/*
Load Jenkins stages from YAML.
Need to support followings
  - load stage from other file
  - load stage dynamically
*/
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

import groovy.transform.InheritConstructors
import groovy.json.JsonSlurper

class Stagefy {
  String filename
  String stagename
  Object script
  def parent
  Stagefy(filename, stagename, parent, script){
    this.filename = filename
    this.stagename = stagename
    this.script = script
    this.parent = parent
  }
  def load_data(String filename, String stagename){
    return this.script.load_data(filename, stagename)
  }
  public def steps_run(){
    def data = load_data(this.filename, this.stagename)
    def pattern = /\$\{\s*env\.[a-z|A-Z|_|\.]*\s*\}*/
    def envData = data["env"]
    def nodeData = data["node"]
    def contents = []
    def moduleprefix = ""
    if(data["modules"] != null ){
      def joined = data["modules"].join(" ")
      moduleprefix = "set +x; source \$MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load ${joined}; set -x;"
    }
    def content = {
      // If parent is parallel, the stage is created
      // if(this.parent != null && this.parent.tpe == "parallels") {
        for(each in data["steps"]){
          if(each.containsKey("sh")){
            def s_shell = each['sh']
            def envList = [:]
            for(eachEnv in s_shell.findAll(pattern)){
              def temp = eachEnv.split("env.")[-1].replace("}","").trim()
              envList[eachEnv] = this.script.env[temp]
            }
            for(eachKey in envList.keySet()){
              s_shell = s_shell.replace(eachKey, envList[eachKey])
            }
            def finaloutput = "${moduleprefix}${s_shell}"
            this.script.sh(finaloutput)
          }else if(each.containsKey("script")){
            def scriptFile = each["script"]
            this.sript.load(scriptFile).main()
          }else if(each.containsKey("setEnvFromFile")){
            this.script.setEnvFromFile(each["setEnvFromFile"])
          }
        }
    }
    contents.add(content)


    if(envData != null){
      def envDataList = []
      def currentContent = contents[-1]
      for(each in envData.keySet()){
        envDataList.add("${each}=${envData[each]}")
      }
      contents.add({
        this.script.withEnv(envDataList){
          currentContent()
        }
      })
    }


    if(nodeData != null){
      def currentContent = contents[-1]
      contents.add({
        this.script.node("${nodeData}"){
          currentContent()
        }
      })
    }

    contents[-1]()
  }

  public def parallels_run(){
    def temp = load_data(this.filename, this.stagename)['parallels']
    def data = [:]
    for(each in temp){
      def nextFile = "\$HERE"
      def nextStage = each

      if(each.contains("from")){
        def splitted = each.split("from")
        nextStage = splitted[0].trim()
        nextFile = splitted[1].trim()
      }
      if(nextFile == "\$HERE"){
        nextFile = this.filename
      }
      data[nextStage] = {
        this.script.stage(nextStage){
          this.construct_stage(nextFile, nextStage).run()
        }

      }


    }
    this.script.parallel data
  }

  public def stages_run(script) {

    println("DEBUGGING :::: ")
    def temp = this.script.load_data(this.filename, this.stagename)['stages']
    println(temp)
    def inside = []
    for(each in temp){
      def nextFile = "\$HERE"
      def nextStage = each
      if(each.contains("from")){
        def splitted = each.split("from")
        nextStage = splitted[0].trim()
        nextFile = splitted[1].trim()
      }
      if(nextFile == "\$HERE"){
        nextFile = this.filename
      }
      inside.add(this.construct_stage(nextFile, nextStage))
    }

    println(inside)
    this.script.sh("echo ${this.stagename} from ${this.filename}")
      for(each in inside){
        this.script.stage(each.stagename){
          each.run()
        }
      }
  }
  def construct_stage(String filename, String stagename) {
    return this.getClass().newInstance(filename, stagename, this, this.script)
  }

  def check_circular_loop(other){
    if (this.parent == null){
      return true
    }else {

      if(this.parent.filename == other.filename && this.parent.stagename == other.stagename){
        throw new Exception("Circular Loop Execution ${this.stagename} from ${other.filename}")
      }else{
        this.parent.check_circular_loop(other)
      }
    }
  }
  public def run(){
    def temp = this.load_data(this.filename, this.stagename)
    def whenData = temp["when"]
    this.script.echo("DEBUG POINT #1")
    this.script.echo("Before data :: ${whenData}")
    if(whenData == null){
      whenData = true
    } else {
      whenData = this.script.evaluation(whenData)
    }
    this.script.echo("After data :: ${whenData}")
    if(whenData){
      if(temp.stages != null){
        this.check_circular_loop(this)
        return this.stages_run()
      }
      else if(temp.parallels != null){
        this.check_circular_loop(this)
        return this.parallels_run()
      }
      else if(temp.steps != null){
        return this.steps_run()
      }
      else {
        this.script.error('No matching type')
      }
    }else [
      Utils.markStageSkippedForConditional(this.stagename)
    ]
  }
}

def run_parallels(name, ss){
  return {
    stage(name) {
      try {
        parallel ss
      } catch(e) {
        throw e
      }
    }
  }
}

def evaluation(value){
  return evaluate(value)
}
def load_data(String filename, String stagename){
  return readYaml(file : filename)[stagename]
}

def construct_stage(String filename, String stagename){
  return new Stagefy(filename, stagename, null, this)
}

def setEnvFromFile(filename){
  def temp = readYaml(file : filename)
  if(temp.env != null){
    for (each in temp.env.keySet()){
      env.setProperty(each, temp.env[each])
    }
  }
}
def run(filename, stagename){
  def temp = construct_stage(filename, stagename)
  stage(stagename){
    temp.run()
  }
}

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
  String filename           // file of stage
  String stagename          // name of stage
  Object script             // wrapper to use global jenkins functions
  boolean flag              // flag of stage. If true, execute the stage, else do not not single stage
  def parent                // parent of current stage
  Stagefy(filename, stagename, parent, script){
    this.filename = filename
    this.stagename = stagename
    this.script = script
    this.flag   = true
    this.parent = parent
  }
  // load stage data from file
  def load_data(String filename, String stagename){
    return this.script.load_data(filename, stagename)
  }

  // single stage run wrapper
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
            this.script.load(scriptFile).main()
          }else if(each.containsKey("setEnvFromFile")){
            this.script.setEnvFromFile(each["setEnvFromFile"])
          }else if(each.containsKey("evaluate")){
            this.script.evaluation(each["evaluate"])
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
    if(this.flag){
      contents[-1]()
    }
  }
  // parallel stage run wrapper
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

  // sequentail stage run wrapper
  public def stages_run(script) {

    def temp = this.script.load_data(this.filename, this.stagename)['stages']
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


      for(each in inside){
        this.script.stage(each.stagename){
          each.run()
        }
      }
  }

  // construct child stage instance
  def construct_stage(String filename, String stagename) {
    return this.getClass().newInstance(filename, stagename, this, this.script)
  }

  // check circluar loop
  def check_circular_loop(other){
    // if no parent, it is passed
    if (this.parent == null){
      return true
    }else {
      /*
        When the parent and other are from the same file and have same stage name,
        it is circular loop
      */
      if(this.parent.filename == other.filename && this.parent.stagename == other.stagename){
        throw new Exception("Circular Loop Execution ${this.stagename} from ${other.filename}")
      }else{
        // Check with current parent tree
        this.parent.check_circular_loop(other)
      }
    }
  }
  public def run(){
    def temp = this.load_data(this.filename, this.stagename)
    def whenData = temp["when"]
    // If when data is empty, it is true by default
    if(whenData == null){
      whenData = true
    } else {
      // otherwise, evaluate the string value
      whenData = this.script.evaluation(whenData)
    }
    this.flag = whenData
    // If parent exists, check the parent skip condition
    if(this.parent != null){
      this.flag = this.parent.flag && this.flag
    }
    // If skipped condition, mark jenkins stage as skipped
    if(!this.flag){
      Utils.markStageSkippedForConditional(this.stagename)
    }
    // Run each type
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

  }
}

// wrapper for evaluation
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

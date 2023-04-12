def singleStage(name, data){
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
    stage(name){
      for(each in data["steps"]){
        if(each.containsKey("sh")){
          def s_shell = each['sh']
          def envList = [:]
          for(eachEnv in s_shell.findAll(pattern)){
            temp = eachEnv.split("env.")[-1].replace("}","").trim()
            envList[eachEnv] = env[temp]
          }
          for(eachKey in envList.keySet()){
            s_shell = s_shell.replace(eachKey, envList[eachKey])
          }
          def finaloutput = "${moduleprefix}${s_shell}"
          sh(finaloutput)
        }else if(each.containsKey("script")){
          def scriptFile = each["script"]
          load(scriptFile).main()
        }
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
      withEnv(envDataList){
        currentContent()
      }
    })
  }


  if(nodeData != null){
    def currentContent = contents[-1]
    contents.add({
      node("${nodeData}"){
        currentContent()
      }
    })
  }

  return contents[-1]
}

def getParallelStages(name, ss) {
  return {
    stage(name){
      try {
        parallel  ss
      } catch (e) {
        throw e
      }
    }
  }
}

def getSequentialStages(name, ss){
  return {
    stage(name){
      try {
        for (each in ss) {
          each()
        }
      } catch (e) {
        throw e
      }
    }
  }
}


def loadStageInfo(filename, stage){
  def tmp = readYaml(file: filename)
  return tmp[stage]
}
@NonCPS
def retrieveStageInfo(data){
  def name      = ""
  def filename  = ""
  def doCommand = null

  if(data.getClass() == String){
    if(data.contains("from")){
      xx = data.split("from")
      name = xx[0].trim()
      filename = xx[1].trim()
    }else {
      name = data
      filename = "\$HERE"
    }
  }else {
    name = data["name"]
    filename = data["file"]
  }
  // if(data.getClass() == String && !data.contains("from")){
  //   name = data
  //   filename = "\$HERE"
  // }else{
  //   name      = data["name"]
  //   filename  = data["file"]
  //   doCommand = data["before"]
  // }

  return [name, filename, doCommand]
}
def getData(name, currentfile, loadedStage = [:]){
  def key = "${currentfile}::${name}"

  def outstage = []

  if (loadedStage[key] != null ){
    if(loadedStage[key].type != "steps"){
      error("Circular Stage maded for ${key} check :: ${loadedStage[key]}")
    }else {
      return  [loadedStage, loadedStage[key].stage[0]]
    }
  }
  else {
    def stageInfo = loadStageInfo(currentfile, name)
    def tpe = "null"
    if (stageInfo.stages != null){
      tpe = "stages"
      def data = []
      for(each in stageInfo["stages"]){
        (newname, newfilename, doCommand) = retrieveStageInfo(each)
        if(doCommand != null) {
          sh "${doCommand}"
        }
        if(newfilename == "\$HERE"){
          newfilename = currentfile
        }

        if (currentfile != newfilename) {
          dd = {
            (loadedStage, dd) = getData(newname, newfilename, loadedStage)
            dd()
          }
        }else {
          (loadedStage, dd) = getData(newname, newfilename, loadedStage)
        }
        data.add(dd)
      }
      outstage.add(getSequentialStages(name, data))
    }else if(stageInfo.parallels != null){
      tpe = "parallels"
      def data1 = [:]
      for(each in stageInfo["parallels"]){
        (newname, newfilename, doCommand) = retrieveStageInfo(each)
        if(doCommand != null) {
          sh "${doCommand}"
        }
        if(newfilename == "\$HERE"){
          newfilename = currentfile
        }

        if (currentfile != newfilename) {
          dd = {
            (loadedStage, dd) = getData(newname, newfilename, loadedStage)
            dd()
          }
        }else {
          (loadedStage, dd) = getData(newname, newfilename, loadedStage)
        }
        data1[newname] = dd
      }
      outstage.add(getParallelStages(name, data1))
    }else if(stageInfo.steps != null){
      tpe = "steps"
      outstage.add(singleStage(name, stageInfo))
    }else {
      error("Stage[${key}] must contains one of [stages, parallels, steps]")
    }
    loadedStage[key] = [type:tpe, stage:outstage]
  }
  return [loadedStage, outstage[0]]
}

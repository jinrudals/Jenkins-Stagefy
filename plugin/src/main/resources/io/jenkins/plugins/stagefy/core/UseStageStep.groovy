package io.jenkins.plugins.stagefy.core

class UseStageStep extends Step {
    UseStageStep(Map raw, def jenkins, Stage stage, String moduleprefix) {
        super(raw, jenkins, stage, moduleprefix)
    }
    void run() {
        String useValue = (String) raw.get("use")
        boolean makeStage = raw.containsKey("makeStage") ? Boolean.parseBoolean(raw.get("makeStage").toString()) : true
        if (!useValue.contains(" from ")) {
            jenkins.error("use directive must follow 'StageName from filepath' format: '${useValue}'")
        }
        def parts = useValue.split(" from ", 2)
        String targetStage = parts[0].trim()
        String targetFile = parts[1].trim()
        Stage child = stage.constructChild(targetFile, targetStage)
        child.checkCircularLoop(child)
        if (makeStage) {
            jenkins.stage(targetStage) { child.run() }
        } else {
            child.run()
        }
    }
}

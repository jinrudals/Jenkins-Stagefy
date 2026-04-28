package io.jenkins.plugins.stagefy.core

class StageFactory {
    static Stage create(String filename, String stagename, Stage parent, def jenkins, Map data) {
        if (data == null) { jenkins.error("Stage '${stagename}' not found in '${filename}'"); return null }
        if (data.containsKey("stages")) return new SequentialStage(filename, stagename, parent, jenkins)
        if (data.containsKey("parallels")) return new ParallelStage(filename, stagename, parent, jenkins)
        if (data.containsKey("steps")) return new StepsStage(filename, stagename, parent, jenkins)
        if (data.containsKey("template")) { jenkins.error("Template stage '${stagename}' cannot be executed directly"); return null }
        jenkins.error("Stage '${stagename}' has no 'stages', 'parallels', or 'steps' key")
        return null
    }
}

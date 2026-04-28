package io.jenkins.plugins.stagefy.core

abstract class Stage implements Serializable {
    String filename
    String stagename
    boolean flag = true
    Stage parent
    def jenkins

    Stage(String filename, String stagename, Stage parent, def jenkins) {
        this.filename = filename
        this.stagename = stagename
        this.parent = parent
        this.jenkins = jenkins
    }

    def load() { return jenkins.loadData(filename, stagename) }

    void checkCircularLoop(Stage other) {
        if (parent == null) return
        if (parent.filename == other.filename && parent.stagename == other.stagename) {
            throw new RuntimeException("Circular Loop Execution ${stagename} from ${other.filename}")
        }
        parent.checkCircularLoop(other)
    }

    void run() {
        Map data = load()
        if (data != null && data.containsKey("template")) {
            jenkins.error("Template stage '${stagename}' cannot be executed directly")
        }
        def whenData = data.get("when")
        flag = (whenData == null) ? true : jenkins.evaluate(whenData)
        if (parent != null) flag = parent.flag && flag
        jenkins.info("Stage ENTER: ${stagename}")
        execute(data)
        jenkins.info("Stage EXIT: ${stagename}")
    }

    abstract void execute(Map data)

    Stage constructChild(String file, String name) {
        def childData = jenkins.loadData(file, name)
        return StageFactory.create(file, name, this, jenkins, childData)
    }

    void executeResolvedSteps(List resolvedSteps, String moduleprefix) {
        for (step in resolvedSteps) {
            jenkins.debug("Step: ${step.keySet()}")
            StepFactory.create(step, jenkins, this, moduleprefix).run()
        }
    }
}

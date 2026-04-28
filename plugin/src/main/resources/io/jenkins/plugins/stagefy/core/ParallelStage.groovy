package io.jenkins.plugins.stagefy.core

import io.jenkins.plugins.stagefy.util.*

class ParallelStage extends Stage {
    ParallelStage(String filename, String stagename, Stage parent, def jenkins) {
        super(filename, stagename, parent, jenkins)
    }

    void execute(Map data) {
        this.checkCircularLoop(this)
        List temp = (List) data.get("parallels")
        Map branches = [:]
        List seenNames = []

        for (each in temp) {
            if (each instanceof String) {
                def parsed = parseStageRef((String) each)
                def capturedStage = parsed[0]
                def capturedFile = parsed[1]
                checkDuplicate(capturedStage, seenNames, "parallels")
                seenNames.add(capturedStage)
                branches[capturedStage] = {
                    jenkins.stage(capturedStage) { constructChild(capturedFile, capturedStage).run() }
                }
            } else if (each instanceof Map) {
                Map entry = (Map) each
                if (entry.containsKey("template")) {
                    Map result = buildTemplateStage(entry, seenNames, "parallels")
                    def capturedName = result.get("name")
                    def capturedSteps = result.get("steps")
                    seenNames.add(capturedName)
                    branches[capturedName] = { jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } }
                } else if (entry.containsKey("iterated")) {
                    for (r in buildIteratedStages(entry, seenNames, "parallels")) {
                        def capturedName = r.get("name")
                        def capturedSteps = r.get("steps")
                        seenNames.add(capturedName)
                        branches[capturedName] = { jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } }
                    }
                }
            }
        }
        jenkins.parallel(branches)
    }

    private List parseStageRef(String ref) {
        if (ref.contains("from")) {
            def parts = ref.split("from", 2)
            return [parts[0].trim(), parts[1].trim()]
        }
        return [ref, this.filename]
    }

    private void checkDuplicate(String name, List seenNames, String block) {
        if (seenNames.contains(name)) jenkins.error("Duplicate stage name '${name}' in ${block} block of '${this.stagename}'")
    }

    private Map buildTemplateStage(Map entry, List seenNames, String block) {
        String templateName = (String) entry.get("template")
        Map bindings = [:]
        for (e in entry.entrySet()) {
            if (!"template".equals(e.key)) bindings.put(e.key, e.value.toString())
        }
        def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
        tpl.validate(jenkins)
        tpl.validateBindings(bindings, jenkins, this.stagename)
        List resolvedSteps = TemplateResolver.resolveStepsWithBindings(tpl.getSteps(), bindings)
        String capturedName = StageNameBuilder.forTemplateRef(templateName, bindings, seenNames)
        checkDuplicate(capturedName, seenNames, block)
        return [name: capturedName, steps: resolvedSteps]
    }

    private List buildIteratedStages(Map entry, List seenNames, String block) {
        Map iteratedData = (Map) entry.get("iterated")
        String templateName = (String) iteratedData.get("template")
        def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
        tpl.validate(jenkins)
        List items = IterationResolver.expandOver(iteratedData.get("over"), jenkins, this.stagename)
        List results = []
        for (int j = 0; j < items.size(); j++) {
            def item = items.get(j)
            String stageName = (item instanceof String)
                    ? StageNameBuilder.forScalar(templateName, (String) item)
                    : StageNameBuilder.forMap(templateName, (Map) item, j)
            Map bindings = IterationResolver.buildBindingsFromItem(templateName, tpl.getArguments(), item, jenkins, this.stagename)
            tpl.validateBindings(bindings, jenkins, this.stagename)
            checkDuplicate(stageName, seenNames, block)
            results.add([name: stageName, steps: TemplateResolver.resolveStepsWithBindings(tpl.getSteps(), bindings)])
        }
        return results
    }
}

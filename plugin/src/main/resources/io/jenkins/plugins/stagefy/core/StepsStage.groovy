package io.jenkins.plugins.stagefy.core

import io.jenkins.plugins.stagefy.util.*

class StepsStage extends Stage {
    StepsStage(String filename, String stagename, Stage parent, def jenkins) {
        super(filename, stagename, parent, jenkins)
    }

    void execute(Map data) {
        List modules = (List) data.get("modules")
        String mp = ModuleResolver.buildPrefix(modules)
        List resolvedSteps = resolveSteps(data)
        def content = { executeResolvedSteps(resolvedSteps, mp) }
        content = wrapWithEnv(content, (Map) data.get("env"))
        content = wrapWithNode(content, data.get("node"))
        if (flag) content()
    }

    private List resolveSteps(Map data) {
        List resolved = []
        List steps = (List) data.get("steps")
        for (each in steps) {
            if (each.containsKey("template")) {
                String templateName = (String) each.get("template")
                Map bindings = [:]
                for (e in each.entrySet()) {
                    if (!"template".equals(e.key)) bindings.put(e.key, e.value.toString())
                }
                def tpl = new Template(templateName, filename, jenkins.loadData(filename, templateName))
                tpl.validate(jenkins)
                tpl.validateBindings(bindings, jenkins, stagename)
                resolved.addAll(TemplateResolver.resolveStepsWithBindings(tpl.getSteps(), bindings))
            } else if (each.containsKey("iterated")) {
                Map iteratedData = (Map) each.get("iterated")
                String templateName = (String) iteratedData.get("template")
                def tpl = new Template(templateName, filename, jenkins.loadData(filename, templateName))
                tpl.validate(jenkins)
                List items = IterationResolver.expandOver(iteratedData.get("over"), jenkins, stagename)
                for (item in items) {
                    Map bindings = IterationResolver.buildBindingsFromItem(templateName, tpl.getArguments(), item, jenkins, stagename)
                    tpl.validateBindings(bindings, jenkins, stagename)
                    resolved.addAll(TemplateResolver.resolveStepsWithBindings(tpl.getSteps(), bindings))
                }
            } else {
                resolved.add(each)
            }
        }
        return resolved
    }

    private Closure wrapWithEnv(Closure content, Map envData) {
        if (envData == null) return content
        List envList = []
        for (e in envData.entrySet()) { envList.add(e.key + "=" + e.value) }
        def cur = content
        return { jenkins.withEnv(envList, cur) }
    }

    private Closure wrapWithNode(Closure content, Object nodeData) {
        if (nodeData == null) return content
        def cur = content
        return { jenkins.node(nodeData.toString(), cur) }
    }
}

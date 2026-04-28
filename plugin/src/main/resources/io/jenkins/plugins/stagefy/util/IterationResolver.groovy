package io.jenkins.plugins.stagefy.util

class IterationResolver implements Serializable {
    static List expandOver(def over, def jenkins, String stagename) {
        if (over instanceof List) return new ArrayList(over)
        if (over instanceof String && over.startsWith('env.')) {
            def varName = over.substring(4)
            def value = jenkins.getEnv(varName)
            if (value == null || value.trim() == '') return []
            def items = []
            for (part in value.split(',')) {
                def trimmed = part.trim()
                if (trimmed) items.add(trimmed)
            }
            return items
        }
        jenkins.error("iterated.over in stage '${stagename}' must be a list or 'env.VAR_NAME' string, got: ${over?.getClass()?.simpleName ?: 'null'}")
    }

    static Map buildBindingsFromItem(String templateName, List declaredArgs, def item, def jenkins, String stagename) {
        def bindings = [:]
        if (item instanceof String) {
            if (declaredArgs.size() != 1) {
                jenkins.error("Template '${templateName}': scalar iteration requires exactly one declared argument")
            }
            bindings[declaredArgs[0]] = item
        } else if (item instanceof Map) {
            for (entry in item.entrySet()) {
                if (entry.key != '_name') bindings[entry.key] = entry.value.toString()
            }
        } else {
            jenkins.error("Template '${templateName}': iterated item must be a scalar string or map")
        }
        return bindings
    }
}

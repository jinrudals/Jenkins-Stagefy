package io.jenkins.plugins.stagefy.util

class TemplateResolver implements Serializable {
    static String substituteBindings(String text, Map bindings) {
        if (text == null) return null
        def result = text
        for (int depth = 0; depth < 5; depth++) {
            def prev = result
            for (entry in bindings.entrySet()) {
                result = result.replace("{${entry.key}}", entry.value.toString())
            }
            if (result == prev) return result
        }
        return result
    }
    static List resolveStepsWithBindings(List steps, Map bindings) {
        def resolved = []
        for (step in steps) {
            def resolvedStep = [:]
            for (entry in step.entrySet()) {
                if (entry.value instanceof String) {
                    resolvedStep[entry.key] = substituteBindings((String) entry.value, bindings)
                } else {
                    resolvedStep[entry.key] = entry.value
                }
            }
            resolved.add(resolvedStep)
        }
        return resolved
    }
}

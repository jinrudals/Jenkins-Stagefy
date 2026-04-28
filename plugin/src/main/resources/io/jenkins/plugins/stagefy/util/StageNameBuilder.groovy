package io.jenkins.plugins.stagefy.util

class StageNameBuilder implements Serializable {
    static String sanitize(String name) {
        return name.replaceAll(/[^A-Za-z0-9_.-]+/, '_')
    }
    static String forScalar(String templateName, String value) {
        return sanitize("${templateName}_${value}")
    }
    static String forMap(String templateName, Map entry, int index) {
        if (entry.containsKey('_name')) return sanitize("${templateName}_${entry._name}")
        return sanitize("${templateName}_${index}")
    }
    static String forTemplateRef(String templateName, Map bindings, List seenNames) {
        def baseName = sanitize(templateName)
        if (!seenNames.contains(baseName)) return baseName
        def suffixParts = []
        for (key in bindings.keySet().sort()) { suffixParts.add(bindings[key].toString()) }
        def candidate = sanitize("${templateName}_${suffixParts.join('_')}")
        if (!seenNames.contains(candidate)) return candidate
        int index = 2
        while (seenNames.contains(sanitize("${candidate}_${index}"))) { index++ }
        return sanitize("${candidate}_${index}")
    }
}

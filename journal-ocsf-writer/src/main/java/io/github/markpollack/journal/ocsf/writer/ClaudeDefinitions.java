package io.github.markpollack.journal.ocsf.writer;

/**
 * Tool declarations in native request-shape and prompt-snapshot records, with schema documents
 * preserved intact.
 */
final class ClaudeDefinitions {
    private ClaudeDefinitions() {}

    static Jv.Arr read(Jv value, String schemaMember) {
        if (!(value instanceof Jv.Arr tools)) {
            return null;
        }
        Jv.Arr definitions = new Jv.Arr();
        for (Jv tool : tools.elements()) {
            Jv.Obj definition =
                    new Jv.Obj()
                            .put("name", tool.member("name"))
                            .put("tool_description", tool.member("description"))
                            .put("definition_format_id", 2)
                            .put("definition_format", "Function declaration")
                            .put("data", tool);
            if (tool.member(schemaMember) instanceof Jv.Obj schema) {
                definition.put("input_schema", new Jv.Obj().put("schema_document", schema));
            }
            if (tool.member("hash") instanceof Jv.Str hash) {
                definition.put("schema_hash", hash);
            }
            definitions.add(definition);
        }
        return definitions;
    }
}

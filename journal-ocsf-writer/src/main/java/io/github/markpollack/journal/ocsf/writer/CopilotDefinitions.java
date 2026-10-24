package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * The tool definitions an OTel span reports in {@code gen_ai.tool.definitions} (a JSON string of function
 * declarations, present under content capture): each as a tool definition in the function-declaration format with its
 * name, description and parameter schema as declared; nothing is reported when the attribute is absent.
 */
final class CopilotDefinitions {

    private CopilotDefinitions() {
    }

    static Jv.Arr of(Jv attribute) {
        Jv parsed = CopilotOtelMapper.parsed(attribute);
        if (!(parsed instanceof Jv.Arr list)) {
            return null;
        }
        Jv.Arr out = new Jv.Arr();
        for (Jv d : list.elements()) {
            Jv.Obj def = new Jv.Obj().put("name", text(d, "name")).put("tool_description", text(d, "description")).put("definition_format_id", 2)
                    .put("definition_format", "Function declaration");
            if (d.member("parameters") instanceof Jv.Obj schema) {
                def.put("input_schema", new Jv.Obj().put("schema_document", schema));
            }
            out.add(def.put("data", d));
        }
        return out;
    }
}

package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Map;
import java.util.Set;

/** Maps the complete tool registry advertised by the verified Antigravity fixture. */
final class AntigravityToolClassifier {

    private static final Map<String, ToolKind> KINDS = Map.ofEntries(
            Map.entry("ask_custom_permission", ToolKind.OTHER),
            Map.entry("ask_permission", ToolKind.OTHER),
            Map.entry("ask_question", ToolKind.OTHER),
            Map.entry("browser_click_element", ToolKind.EXECUTE),
            Map.entry("browser_drag_pixel_to_pixel", ToolKind.EXECUTE),
            Map.entry("browser_get_dom", ToolKind.READ),
            Map.entry("browser_get_network_request", ToolKind.READ),
            Map.entry("browser_input", ToolKind.EXECUTE),
            Map.entry("browser_list_network_requests", ToolKind.READ),
            Map.entry("browser_mouse_down", ToolKind.EXECUTE),
            Map.entry("browser_mouse_up", ToolKind.EXECUTE),
            Map.entry("browser_move_mouse", ToolKind.EXECUTE),
            Map.entry("browser_press_key", ToolKind.EXECUTE),
            Map.entry("browser_refresh_page", ToolKind.FETCH),
            Map.entry("browser_resize_window", ToolKind.EXECUTE),
            Map.entry("browser_scroll", ToolKind.EXECUTE),
            Map.entry("browser_scroll_dom", ToolKind.EXECUTE),
            Map.entry("browser_select_option", ToolKind.EXECUTE),
            Map.entry("browser_subagent", ToolKind.THINK),
            Map.entry("call_mcp_tool", ToolKind.OTHER),
            Map.entry("capture_browser_console_logs", ToolKind.READ),
            Map.entry("capture_browser_screenshot", ToolKind.READ),
            Map.entry("click_browser_pixel", ToolKind.EXECUTE),
            Map.entry("command_status", ToolKind.READ),
            Map.entry("define_subagent", ToolKind.THINK),
            Map.entry("delete_knowledge", ToolKind.DELETE),
            Map.entry("execute_browser_javascript", ToolKind.EXECUTE),
            Map.entry("find_by_name", ToolKind.SEARCH),
            Map.entry("finish", ToolKind.OTHER),
            Map.entry("generate_image", ToolKind.OTHER),
            Map.entry("grep_search", ToolKind.SEARCH),
            Map.entry("invoke_subagent", ToolKind.THINK),
            Map.entry("list_browser_pages", ToolKind.READ),
            Map.entry("list_dir", ToolKind.READ),
            Map.entry("list_permissions", ToolKind.READ),
            Map.entry("list_resources", ToolKind.READ),
            Map.entry("manage_inbox", ToolKind.THINK),
            Map.entry("manage_subagents", ToolKind.THINK),
            Map.entry("manage_task", ToolKind.THINK),
            Map.entry("multi_replace_file_content", ToolKind.EDIT),
            Map.entry("notebook_edit", ToolKind.EDIT),
            Map.entry("notebook_execution", ToolKind.EXECUTE),
            Map.entry("open_browser_url", ToolKind.FETCH),
            Map.entry("read_browser_page", ToolKind.READ),
            Map.entry("read_resource", ToolKind.READ),
            Map.entry("read_url_content", ToolKind.FETCH),
            Map.entry("replace_file_content", ToolKind.EDIT),
            Map.entry("run_command", ToolKind.EXECUTE),
            Map.entry("schedule", ToolKind.THINK),
            Map.entry("search_web", ToolKind.SEARCH),
            Map.entry("sed_file", ToolKind.READ),
            Map.entry("send_command_input", ToolKind.EXECUTE),
            Map.entry("send_message", ToolKind.OTHER),
            Map.entry("view_file", ToolKind.READ),
            Map.entry("wait", ToolKind.THINK),
            Map.entry("wait_5_seconds", ToolKind.THINK),
            Map.entry("write_to_file", ToolKind.EDIT));

    private AntigravityToolClassifier() {
    }

    static ToolKind classify(String rawName) {
        return rawName == null ? ToolKind.OTHER : KINDS.getOrDefault(rawName, ToolKind.OTHER);
    }

    static Set<String> knownToolNames() {
        return KINDS.keySet();
    }
}

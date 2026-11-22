package com.doudou.x.eval.cases;

import com.doudou.x.ai.device.NativeToolRegistry;
import com.doudou.x.eval.EvalCase;
import com.doudou.x.eval.EvalResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 工具门控用例：写工具默认不下发、只读工具不误判为写、已移除的日历工具不再出现。 */
public final class ToolGatingCase implements EvalCase {

    private static final String[] REMOVED_CALENDAR_TOOLS = {
            "list_calendars", "query_calendar_events", "update_calendar_event",
            "delete_calendar_event"
    };

    @Override
    public String id() {
        return "tool.gating";
    }

    @Override
    public String description() {
        return "只读工具默认下发、写工具需开关、已移除工具不再暴露给模型";
    }

    @Override
    public List<EvalResult> run() {
        List<EvalResult> out = new ArrayList<>();

        EvalResult.add(out, id() + ".新增日程属于写工具",
                NativeToolRegistry.isWriteTool("add_calendar_event"), "");
        EvalResult.add(out, id() + ".联网搜索不是写工具",
                !NativeToolRegistry.isWriteTool("web_search"), "");
        EvalResult.add(out, id() + ".设备信息不是写工具",
                !NativeToolRegistry.isWriteTool("get_device_info"), "");

        JSONArray readOnly = NativeToolRegistry.definitions(false);
        EvalResult.add(out, id() + ".关闭写开关时仍有只读工具", readOnly.length() > 0,
                "count=" + readOnly.length());
        EvalResult.add(out, id() + ".关闭写开关不下发写工具",
                !names(readOnly).contains("add_calendar_event"), "");

        JSONArray all = NativeToolRegistry.definitions(true);
        List<String> allNames = names(all);
        for (String removed : REMOVED_CALENDAR_TOOLS) {
            EvalResult.add(out, id() + ".已移除 " + removed,
                    !allNames.contains(removed), "");
        }
        return out;
    }

    private static List<String> names(JSONArray definitions) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject tool = definitions.optJSONObject(i);
            if (tool == null) {
                continue;
            }
            JSONObject function = tool.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", "");
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }
}

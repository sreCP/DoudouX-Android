package com.doudou.x.eval.cases;

import com.doudou.x.ai.openai.SseEventParser;
import com.doudou.x.ai.openai.ToolCallAccumulator;
import com.doudou.x.eval.EvalCase;
import com.doudou.x.eval.EvalResult;
import com.doudou.x.model.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 工具调用分片合并用例：id / name / arguments 跨 chunk 拼接后必须完整且可解析。 */
public final class ToolCallMergeCase implements EvalCase {

    @Override
    public String id() {
        return "toolcall.merge";
    }

    @Override
    public String description() {
        return "tool_calls 分片合并成合法调用，参数 JSON 不丢字符";
    }

    @Override
    public List<EvalResult> run() {
        List<EvalResult> out = new ArrayList<>();
        ToolCallAccumulator accumulator = new ToolCallAccumulator();

        // 非法尾串不能把解析层打崩
        boolean malformedSafe = true;
        try {
            accumulator.merge(SseEventParser.parse("{bad json"));
        } catch (Throwable t) {
            malformedSafe = false;
        }
        EvalResult.add(out, id() + ".非法分片容错", malformedSafe, "");

        // 一个调用的名字和参数被拆到三个 chunk：name 分两段，arguments 分三段
        feed(accumulator, fragment(0, "call_abc", "add_cal", "{\"title\":"));
        feed(accumulator, fragment(0, null, "endar_event", "开"));
        feed(accumulator, fragment(0, null, null, "会\"}"));
        // 第二个调用，验证按 index 分开累积
        feed(accumulator, fragment(1, "call_def", "get_time", "{}"));

        List<ToolCall> calls = accumulator.build();
        EvalResult.add(out, id() + ".调用条数", calls.size() == 2, "实际=" + calls.size());

        if (calls.size() >= 1) {
            ToolCall first = calls.get(0);
            EvalResult.add(out, id() + ".名称跨片拼接",
                    "add_calendar_event".equals(first.getName()), "name=" + first.getName());
            EvalResult.add(out, id() + ".ID 保留",
                    "call_abc".equals(first.getId()), "id=" + first.getId());
            boolean validJson = false;
            String title = "";
            try {
                JSONObject args = new JSONObject(first.getArguments());
                title = args.optString("title", "");
                validJson = "开会".equals(title);
            } catch (Exception ignored) {
                // 参数不是合法 JSON，下面的断言会报出来
            }
            EvalResult.add(out, id() + ".参数 JSON 合法", validJson,
                    "arguments=" + first.getArguments() + "，title=" + title);
        }
        if (calls.size() >= 2) {
            EvalResult.add(out, id() + ".多调用隔离",
                    "get_time".equals(calls.get(1).getName()),
                    "name=" + calls.get(1).getName());
        }
        return out;
    }

    private static void feed(ToolCallAccumulator accumulator, String payload) {
        accumulator.merge(SseEventParser.parse(payload));
    }

    /** 用 JSONObject 拼一条带 tool_calls 分片的 SSE payload，避免手写转义出错。 */
    private static String fragment(int index, String id, String name, String arguments) {
        try {
            JSONObject function = new JSONObject();
            if (name != null) {
                function.put("name", name);
            }
            if (arguments != null) {
                function.put("arguments", arguments);
            }
            JSONObject call = new JSONObject();
            call.put("index", index);
            if (id != null) {
                call.put("id", id);
            }
            call.put("function", function);

            JSONArray calls = new JSONArray();
            calls.put(call);
            JSONObject delta = new JSONObject();
            delta.put("tool_calls", calls);
            JSONObject choice = new JSONObject();
            choice.put("delta", delta);
            JSONArray choices = new JSONArray();
            choices.put(choice);
            JSONObject root = new JSONObject();
            root.put("choices", choices);
            return root.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

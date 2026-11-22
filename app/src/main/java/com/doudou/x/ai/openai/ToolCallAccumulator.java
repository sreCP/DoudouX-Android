package com.doudou.x.ai.openai;

import android.util.SparseArray;

import com.doudou.x.model.ToolCall;
import com.doudou.x.util.JsonUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * tool_calls 流式分片累积器。
 *
 * <p>OpenAI 的流式工具调用不是一个完整 JSON，而是按 {@code index} 把
 * id / name / arguments 拆成多片陆续送达，所以只能追加、不能覆盖。
 * 解析（{@link SseEventParser}）与累积（本类）分开：解析管「这一行长什么样」，
 * 累积管「跨行怎么拼回一个调用」。
 */
public final class ToolCallAccumulator {

    private final SparseArray<Builder> builders = new SparseArray<>();

    /** 把一条事件里的分片并入累积器；事件为 null 或没有分片时安全返回。 */
    public void merge(SseEvent event) {
        if (event == null || event.toolCallDeltas == 0 || event.toolCallArray == null) {
            return;
        }
        for (int i = 0; i < event.toolCallDeltas; i++) {
            JSONObject call = event.toolCallArray.optJSONObject(i);
            if (call == null) {
                continue;
            }
            int index = call.optInt("index", i);
            Builder builder = builders.get(index);
            if (builder == null) {
                builder = new Builder();
                builders.put(index, builder);
            }
            String id = call.optString("id", null);
            if (!JsonUtils.isLiteralNull(id) && !id.isEmpty()) {
                builder.id = id;
            }
            JSONObject function = call.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", null);
            if (!JsonUtils.isLiteralNull(name) && !name.isEmpty()) {
                builder.name.append(name);
            }
            String arguments = function.optString("arguments", null);
            if (!JsonUtils.isLiteralNull(arguments) && !arguments.isEmpty()) {
                builder.arguments.append(arguments);
            }
        }
    }

    /** 已累积的调用个数（含 name 尚未到齐的）。 */
    public int size() {
        return builders.size();
    }

    /** 把分片整理成可执行的工具调用列表；name 为空的残缺分片会被丢弃。 */
    public List<ToolCall> build() {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            Builder builder = builders.valueAt(i);
            if (builder == null || builder.name.length() == 0) {
                continue;
            }
            ToolCall call = new ToolCall();
            call.setId(builder.id == null || builder.id.isEmpty() ? "call_" + i : builder.id);
            call.setName(builder.name.toString());
            call.setArguments(builder.arguments.toString());
            calls.add(call);
        }
        return calls;
    }

    /** 单个调用的分片累积状态。 */
    public static final class Builder {
        public String id;
        public final StringBuilder name = new StringBuilder();
        public final StringBuilder arguments = new StringBuilder();
    }
}

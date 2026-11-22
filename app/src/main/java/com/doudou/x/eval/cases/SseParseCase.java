package com.doudou.x.eval.cases;

import com.doudou.x.ai.openai.SseEvent;
import com.doudou.x.ai.openai.SseEventParser;
import com.doudou.x.eval.EvalCase;
import com.doudou.x.eval.EvalResult;
import com.doudou.x.util.JsonUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 解析用例：验证「一次解析同时取出增量文本、结束原因、工具分片」。
 *
 * <p>直接调用 {@link SseEventParser#parse(String)}，测的就是线上那份代码，
 * 不是评测里复制出来的副本。
 */
public final class SseParseCase implements EvalCase {

    @Override
    public String id() {
        return "sse.parse";
    }

    @Override
    public String description() {
        return "SSE 单事件解析同时取出文本、思考、结束原因与工具分片";
    }

    @Override
    public List<EvalResult> run() {
        List<EvalResult> out = new ArrayList<>();

        SseEvent textOnly = SseEventParser.parse(
                "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}");
        EvalResult.add(out, id() + ".纯文本增量",
                textOnly != null && "你好".equals(textOnly.answer)
                        && textOnly.reasoning.isEmpty() && textOnly.finishReason == null,
                textOnly == null ? "解析返回 null" : "answer=" + textOnly.answer);

        SseEvent reasoning = SseEventParser.parse(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先想想\"}}]}");
        EvalResult.add(out, id() + ".思考字段兼容",
                reasoning != null && "先想想".equals(reasoning.reasoning),
                reasoning == null ? "解析返回 null" : "reasoning=" + reasoning.reasoning);

        SseEvent stop = SseEventParser.parse(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        EvalResult.add(out, id() + ".结束原因",
                stop != null && "stop".equals(stop.finishReason),
                stop == null ? "解析返回 null" : "finish=" + stop.finishReason);

        SseEvent withTools = SseEventParser.parse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":["
                        + "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]}}]}");
        EvalResult.add(out, id() + ".工具分片计数",
                withTools != null && withTools.toolCallDeltas == 1 && withTools.toolCallArray != null,
                withTools == null ? "解析返回 null" : "deltas=" + withTools.toolCallDeltas);

        SseEvent jsonNull = SseEventParser.parse(
                "{\"choices\":[{\"delta\":{\"content\":null}}]}");
        EvalResult.add(out, id() + ".JSON null 归一",
                jsonNull != null && jsonNull.answer.isEmpty(),
                jsonNull == null ? "解析返回 null" : "answer=" + jsonNull.answer);

        EvalResult.add(out, id() + ".非法 JSON 返回 null",
                SseEventParser.parse("{not json") == null, "");
        EvalResult.add(out, id() + ".无 choices 返回 null",
                SseEventParser.parse("{}") == null, "");

        // optText 对 JSON null 的处理单独守一次，避免以后改解析时把 null 当成字面量
        try {
            JSONObject obj = new JSONObject("{\"v\":null}");
            EvalResult.add(out, id() + ".optText 空值",
                    JsonUtils.optText(obj, "v").isEmpty(), "");
        } catch (Exception e) {
            EvalResult.add(out, id() + ".optText 空值", false, e.getMessage());
        }
        return out;
    }
}

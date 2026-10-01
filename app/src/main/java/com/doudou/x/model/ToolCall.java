package com.doudou.x.model;

import org.json.JSONObject;

/**
 * 一次工具调用（OpenAI 兼容格式的 tool_calls）。
 *
 * 由模型发起（id / name / arguments），客户端执行后把结果写进 result，
 * 用于界面展示与下一轮请求回传。
 */
public class ToolCall {

    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_ARGUMENTS = "arguments";
    private static final String JSON_RESULT = "result";

    private String id;
    private String name;
    /** JSON 字符串形式的参数（模型可能流式拼出，不能假设一定合法）。 */
    private String arguments;
    /** 本地执行后的结果文本。 */
    private String result;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(JSON_ID, id);
            obj.put(JSON_NAME, name);
            obj.put(JSON_ARGUMENTS, arguments);
            obj.put(JSON_RESULT, result);
        } catch (Exception ignored) {
            // key 均非空，不会发生
        }
        return obj;
    }

    public static ToolCall fromJson(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        ToolCall call = new ToolCall();
        call.setId(obj.optString(JSON_ID, ""));
        call.setName(obj.optString(JSON_NAME, ""));
        call.setArguments(obj.optString(JSON_ARGUMENTS, ""));
        call.setResult(obj.optString(JSON_RESULT, ""));
        return call;
    }
}

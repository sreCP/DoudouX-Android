package com.doudou.x.ai.device;

import android.util.Log;

import com.doudou.x.DoudouApplication;
import com.doudou.x.data.TavilyConfigStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 联网搜索工具：直接走 Tavily Search REST API，不引入三方 SDK。
 *
 * <p>省钱配置（免费套餐 1000 credits/月）：
 * <ul>
 *   <li>{@code search_depth=basic}：每次搜索 1 credit（advanced 要 2 credits）；</li>
 *   <li>{@code max_results} 默认 3、硬上限 5：减少回灌给模型的 token；</li>
 *   <li>{@code chunks_per_source=1}：摘要只取一段；</li>
 *   <li>{@code include_answer/include_raw_content/include_images=false}：不取额外内容。</li>
 * </ul>
 *
 * <p>只读工具，不改动本机数据；没有配置 API Key 时，整个工具集不下发。
 */
public final class WebSearchTool implements NativeToolSet {

    private static final String LOG_TAG = "DoudouX-Web";

    public static final String WEB_SEARCH = "web_search";

    private static final String ENDPOINT = "https://api.tavily.com/search";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_RESULTS = 3;
    private static final int HARD_MAX_RESULTS = 5;
    private static final int MAX_SNIPPET_CHARS = 400;

    @Override
    public JSONArray definitions(boolean includeWrite) {
        JSONArray array = new JSONArray();
        try {
            JSONObject props = new JSONObject();
            putStringProp(props, "query", "要搜索的问题或关键词，必填");
            putIntProp(props, "max_results", "可选，返回条数，默认 3，最多 5");

            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("properties", props);
            params.put("required", stringArray("query"));

            array.put(function(WEB_SEARCH,
                    "联网搜索最新信息。当用户问到实时新闻、近期事件、价格或其他本地知识无法回答的问题时调用。",
                    params));
        } catch (Exception ignored) {
            // 参数固定，不会发生
        }
        return array;
    }

    @Override
    public String execute(String name, JSONObject args) {
        if (!WEB_SEARCH.equals(name)) {
            return null;
        }
        return search(args);
    }

    @Override
    public boolean isWriteTool(String name) {
        return false;
    }

    @Override
    public boolean isAvailable() {
        TavilyConfigStore config = config();
        return config != null && config.isConfigured();
    }

    @Override
    public String unavailableMessage() {
        return "尚未配置联网搜索 API Key，请在设置页「网络搜索」中填写后重试";
    }

    private String search(JSONObject args) {
        TavilyConfigStore config = config();
        if (config == null || !config.isConfigured()) {
            return unavailableMessage();
        }
        String query = args.optString("query", "").trim();
        if (query.isEmpty()) {
            return "缺少搜索关键词 query";
        }
        int maxResults = args.optInt("max_results", DEFAULT_MAX_RESULTS);
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        maxResults = Math.min(maxResults, HARD_MAX_RESULTS);

        JSONObject body = new JSONObject();
        try {
            body.put("query", query);
            body.put("search_depth", "basic");
            body.put("topic", "general");
            body.put("max_results", maxResults);
            body.put("chunks_per_source", 1);
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            body.put("include_images", false);
        } catch (Exception ignored) {
            // key 固定，不会发生
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            DataOutputStream os = new DataOutputStream(conn.getOutputStream());
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStream(stream);
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(LOG_TAG, "Tavily 返回 HTTP " + code + "：" + clip(raw, 200));
                return "联网搜索失败（HTTP " + code + "）：" + friendlyError(code);
            }

            JSONObject json = new JSONObject(raw);
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return "联网搜索没有找到「" + query + "」的相关结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("联网搜索「").append(query).append("」，返回 ")
                    .append(Math.min(results.length(), maxResults)).append(" 条：\n");
            int count = 0;
            for (int i = 0; i < results.length() && count < maxResults; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                count++;
                String title = item.optString("title", "").trim();
                String link = item.optString("url", "").trim();
                String snippet = normalize(item.optString("content", ""));
                sb.append(count).append(". ").append(title.isEmpty() ? "(无标题)" : title).append('\n');
                if (!link.isEmpty()) {
                    sb.append("   链接：").append(link).append('\n');
                }
                if (!snippet.isEmpty()) {
                    sb.append("   摘要：").append(clip(snippet, MAX_SNIPPET_CHARS)).append('\n');
                }
            }
            Log.d(LOG_TAG, "联网搜索完成，条数=" + count);
            return sb.toString();
        } catch (Exception e) {
            Log.e(LOG_TAG, "联网搜索失败：" + e.getMessage());
            return "联网搜索失败：" + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static TavilyConfigStore config() {
        DoudouApplication app = DoudouApplication.getInstance();
        return app == null ? null : TavilyConfigStore.getInstance(app.getApplicationContext());
    }

    private static String readStream(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // 返回已读到的部分
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        return sb.toString();
    }

    private static String friendlyError(int code) {
        if (code == 401) {
            return "API Key 无效，请检查设置里的 Tavily API Key";
        }
        if (code == 422) {
            return "搜索参数不被接受，请换个更简短的关键词";
        }
        if (code == 429) {
            return "请求过于频繁，请稍后再试";
        }
        if (code == 432 || code == 433 || code == 434) {
            return "免费套餐额度或容量受限，请稍后再试";
        }
        return "Tavily 服务暂时不可用，请稍后再试";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static JSONArray stringArray(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static void putStringProp(JSONObject props, String name, String description) {
        try {
            JSONObject prop = new JSONObject();
            prop.put("type", "string");
            prop.put("description", description);
            props.put(name, prop);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
    }

    private static void putIntProp(JSONObject props, String name, String description) {
        try {
            JSONObject prop = new JSONObject();
            prop.put("type", "integer");
            prop.put("description", description);
            props.put(name, prop);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
    }

    private static JSONObject function(String name, String description, JSONObject parameters) {
        JSONObject function = new JSONObject();
        JSONObject tool = new JSONObject();
        try {
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", parameters);
            tool.put("type", "function");
            tool.put("function", function);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
        return tool;
    }
}

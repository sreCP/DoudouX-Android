package com.doudou.x.eval.cases;

import com.doudou.x.ai.memory.MemoryItem;
import com.doudou.x.ai.memory.MemoryRetriever;
import com.doudou.x.ai.memory.MemoryType;
import com.doudou.x.eval.EvalCase;
import com.doudou.x.eval.EvalResult;

import java.util.ArrayList;
import java.util.List;

/** 记忆检索用例：相似度、程序记忆常驻、半衰期衰减、同 key 覆盖。 */
public final class MemoryRetrievalCase implements EvalCase {

    @Override
    public String id() {
        return "memory.retrieval";
    }

    @Override
    public String description() {
        return "bigram 相似度、程序记忆常驻、半衰期衰减与冲突消解行为稳定";
    }

    @Override
    public List<EvalResult> run() {
        List<EvalResult> out = new ArrayList<>();

        float same = MemoryRetriever.similarity("记忆系统", "记忆系统");
        EvalResult.add(out, id() + ".相同文本相似度为 1", same > 0.99f, "similarity=" + same);
        float diff = MemoryRetriever.similarity("天气", "用户偏好");
        EvalResult.add(out, id() + ".无关文本相似度为 0", diff < 0.01f, "similarity=" + diff);

        List<MemoryItem> pool = new ArrayList<>();
        pool.add(new MemoryItem("p1", MemoryType.PROCEDURAL, "输出格式", "先给结论再给理由", 0.9f));
        pool.add(new MemoryItem("s1", MemoryType.SEMANTIC, "用户职业", "用户是 Android 工程师", 0.9f));
        pool.add(new MemoryItem("s2", MemoryType.SEMANTIC, "城市", "用户在北京", 0.9f));
        List<MemoryItem> recalled = MemoryRetriever.recall(pool, "完全不相关的提问", 2);

        boolean proceduralKept = false;
        for (MemoryItem item : recalled) {
            if ("p1".equals(item.getId())) {
                proceduralKept = true;
            }
        }
        EvalResult.add(out, id() + ".程序记忆常驻", proceduralKept,
                "recalled=" + recalled.size());

        MemoryItem fresh = new MemoryItem("h1", MemoryType.SEMANTIC, "衰减测试", "内容", 1f);
        long now = System.currentTimeMillis();
        float scoreNow = fresh.score(now);
        long halfLife = MemoryType.SEMANTIC.getHalfLifeMs();
        float scoreLater = fresh.score(now + halfLife);
        EvalResult.add(out, id() + ".半衰期减半",
                scoreLater > 0f && scoreLater < scoreNow * 0.75f,
                "now=" + scoreNow + "，halfLife 后=" + scoreLater);

        String oldKey = fresh.getKey();
        fresh.overwrite("新内容", 0.8f);
        EvalResult.add(out, id() + ".同 key 覆盖保留主键",
                oldKey.equals(fresh.getKey()) && "新内容".equals(fresh.getContent())
                        && "h1".equals(fresh.getId()), "key=" + oldKey);
        return out;
    }
}

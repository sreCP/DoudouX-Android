package com.doudou.x.eval;

import com.doudou.x.eval.cases.ContextBudgetCase;
import com.doudou.x.eval.cases.MemoryRetrievalCase;
import com.doudou.x.eval.cases.SseParseCase;
import com.doudou.x.eval.cases.ToolCallMergeCase;
import com.doudou.x.eval.cases.ToolGatingCase;

import java.util.ArrayList;
import java.util.List;

/** 评测入口：顺序跑完全部用例，单条用例异常不会中断整轮。 */
public final class EvalRunner {

    private static final List<EvalCase> CASES = new ArrayList<>();

    static {
        CASES.add(new SseParseCase());
        CASES.add(new ToolCallMergeCase());
        CASES.add(new ContextBudgetCase());
        CASES.add(new MemoryRetrievalCase());
        CASES.add(new ToolGatingCase());
    }

    private EvalRunner() {
    }

    public static EvalReport runAll() {
        long start = System.currentTimeMillis();
        List<EvalResult> all = new ArrayList<>();
        for (EvalCase evalCase : CASES) {
            try {
                List<EvalResult> part = evalCase.run();
                if (part != null) {
                    all.addAll(part);
                }
            } catch (Throwable t) {
                // 评测自身出错也要暴露，不能静默跳过——否则会误以为通过
                EvalResult.add(all, evalCase.id() + " 执行异常", false,
                        t.getClass().getSimpleName() + "：" + t.getMessage());
            }
        }
        return new EvalReport(all, System.currentTimeMillis() - start);
    }
}

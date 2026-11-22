package com.doudou.x.eval;

import java.util.ArrayList;
import java.util.List;

/** 一次评测的汇总报告。 */
public final class EvalReport {

    public final List<EvalResult> results;
    public final long durationMs;

    public EvalReport(List<EvalResult> results, long durationMs) {
        this.results = results == null ? new ArrayList<EvalResult>() : results;
        this.durationMs = durationMs;
    }

    public int passedCount() {
        int n = 0;
        for (EvalResult r : results) {
            if (r.passed) {
                n++;
            }
        }
        return n;
    }

    public int failedCount() {
        return results.size() - passedCount();
    }

    public boolean allPassed() {
        return failedCount() == 0;
    }

    /** 中文纯文本报告，可直接复制到 issue 或简历佐证里。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime 自检：").append(passedCount()).append('/').append(results.size())
                .append(" 通过，耗时 ").append(durationMs).append("ms\n");
        for (EvalResult r : results) {
            sb.append(r.passed ? "  [通过] " : "  [失败] ").append(r.name);
            if (!r.detail.isEmpty()) {
                sb.append(" — ").append(r.detail);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}

package com.doudou.x.ai.context;

/**
 * 上下文预算：把可用的输入窗口按优先级切给各层，超预算时从优先级最低的层开始砍。
 *
 * <p>优先级由高到低：系统提示词 → 工具声明 → 最近若干轮 → 摘要 → 更老的历史。
 * 前两层只做上限保护、不参与裁剪，因为砍掉它们会直接改变模型行为。
 */
public final class ContextBudget {

    /** 默认输入窗口预算，按 8K 估；服务端窗口更大时可整体调大。 */
    public static final int DEFAULT_TOTAL = 8192;

    private final int totalTokens;
    /** 给模型输出预留的部分，参与总量扣减，避免输入吃满窗口。 */
    private final int outputReserve;
    private final int systemReserve;
    private final int toolReserve;
    private final int summaryReserve;
    /** 长期记忆块的上限。 */
    private final int memoryReserve;
    /** 最近多少轮（一轮 = 一条 user 及其后续）原样保留，不参与裁剪。 */
    private final int keepRecentTurns;
    /** 压缩时保留最近多少轮不被压进摘要。 */
    private final int compactKeepTurns;

    private ContextBudget(int totalTokens, int outputReserve, int systemReserve,
                          int toolReserve, int summaryReserve, int memoryReserve,
                          int keepRecentTurns, int compactKeepTurns) {
        this.totalTokens = totalTokens;
        this.outputReserve = outputReserve;
        this.systemReserve = systemReserve;
        this.toolReserve = toolReserve;
        this.summaryReserve = summaryReserve;
        this.memoryReserve = memoryReserve;
        this.keepRecentTurns = keepRecentTurns;
        this.compactKeepTurns = compactKeepTurns;
    }

    /** 默认预算：8K 窗口，留 1K 给输出，最近 3 轮硬保留，压缩时留 2 轮。 */
    public static ContextBudget defaults() {
        return new ContextBudget(DEFAULT_TOTAL, 1024, 512, 512, 600, 400, 3, 2);
    }

    /** 按窗口大小生成一套预算，各层配额按默认比例缩放。 */
    public static ContextBudget forWindow(int windowTokens) {
        int total = Math.max(2048, windowTokens);
        return new ContextBudget(total, total / 8, total / 16,
                total / 16, total / 12, total / 20, 3, 2);
    }

    /** 扣掉系统提示词、工具声明、摘要与记忆后，留给对话历史的预算。 */
    public int historyBudget(int systemTokens, int toolTokens,
                             int summaryTokens, int memoryTokens) {
        int used = Math.min(systemTokens, systemReserve)
                + Math.min(toolTokens, toolReserve)
                + Math.min(summaryTokens, summaryReserve)
                + Math.min(memoryTokens, memoryReserve);
        int left = totalTokens - outputReserve - used;
        // 再紧也要给最近一轮留位置，否则模型看不到当前提问
        return Math.max(left, 256);
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    public int getCompactKeepTurns() {
        return compactKeepTurns;
    }

    public int getSummaryReserve() {
        return summaryReserve;
    }
}

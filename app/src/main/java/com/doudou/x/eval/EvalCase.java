package com.doudou.x.eval;

import java.util.List;

/**
 * 一条 L0 确定性评测用例。
 *
 * <p>只测「给定输入 → 真实模块输出」的不变量，不调用 LLM、不联网、不依赖设备状态。
 */
public interface EvalCase {

    /** 稳定标识，用于报告与历史对比。 */
    String id();

    /** 中文说明：这条用例在守什么。 */
    String description();

    /** 执行并返回若干条断言结果。 */
    List<EvalResult> run();
}

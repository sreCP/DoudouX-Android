package com.doudou.x.eval;

import java.util.List;

/** 一条断言的结果。 */
public final class EvalResult {

    public final String name;
    public final boolean passed;
    public final String detail;

    public EvalResult(String name, boolean passed, String detail) {
        this.name = name;
        this.passed = passed;
        this.detail = detail == null ? "" : detail;
    }

    /** 便捷写法：往用例结果列表里追加一条断言。 */
    public static void add(List<EvalResult> out, String name, boolean passed, String detail) {
        out.add(new EvalResult(name, passed, detail));
    }
}

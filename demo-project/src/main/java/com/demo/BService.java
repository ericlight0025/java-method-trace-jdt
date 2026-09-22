package com.demo;

/**
 * Demo 呼叫鏈的中間 Service。
 */
public class BService {

    private final CService cService = new CService();

    /**
     * 從 BService 呼叫 CService。
     *
     * @return CService 的處理結果
     */
    public String process() {
        return cService.calculate();
    }
}

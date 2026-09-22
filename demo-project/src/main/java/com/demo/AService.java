package com.demo;

/**
 * Demo 呼叫鏈的起始 Service。
 */
public class AService {

    private final BService bService = new BService();

    /**
     * 從 AService 呼叫 BService。
     *
     * @return BService 的處理結果
     */
    public String execute() {
        return bService.process();
    }
}

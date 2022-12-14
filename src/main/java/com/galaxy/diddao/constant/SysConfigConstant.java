package com.galaxy.diddao.constant;

/**
 * @Author Ant
 * @Date 2022/9/17 16:08
 * @Description:
 */
public interface SysConfigConstant {

    /**
     *  监控合约地址key
     */
    String MONITOR_CONTRACT_ADDRESS_KEY = "monitor_contract_address";

    /**
     * 监控区块高度key
     */
    String MONITOR_BLOCK_HEIGHT_KEY = "monitor_block_height";

    /**
     * 监控安全区块（Finality）阈值
     */
    String MONITOR_BLOCK_THRESHOLD_KEY = "monitor_block_threshold";

    /**
     * 方法签名和对应的解析处理类
     */
    String MONITOR_EVENT_PARSE_KEY = "monitor_event_parse_key";
}

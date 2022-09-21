package com.hw.did.task;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hw.did.config.BlockConfig;
import com.hw.did.constant.SysConfigConstant;
import com.hw.did.dto.BlockTxDetailInfo;
import com.hw.did.dto.BlockTxInfo;
import com.hw.did.dto.Logs;
import com.hw.did.service.FunctionEventParseService;
import com.hw.did.service.SysConfigService;
import com.hw.did.utils.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @Author leyangjie
 * @Date 2022/9/17 11:14
 * @Description: 同步event事件并解析数据，写入到mysql数据库
 */
@Slf4j
@Component
public class SyncEventTask implements CommandLineRunner {


    @Autowired
    private SysConfigService sysConfigService;

    @Autowired
    private BlockConfig blockConfig;

    @Override
    public void run(String... args) throws Exception {
        Executors.newFixedThreadPool(1)
                .execute(this::asyncHandleTask);

    }

    private void asyncHandleTask() {
        for (; ; ) {
            // step1 获取当前处理的块高度
            Integer monitorBlockHeight = Optional.ofNullable(sysConfigService.getValue(SysConfigConstant.MONITOR_BLOCK_HEIGHT_KEY))
                    .map(Integer::parseInt).orElse(0);

            // step2 获取设置的区块阈值
            Integer blockHeightThreshold = Optional.ofNullable(sysConfigService.getCacheValue(SysConfigConstant.MONITOR_BLOCK_THRESHOLD_KEY))
                    .map(Integer::parseInt).orElse(0);

            // step3 获取区块链目前最大区块高度
            Integer curMaxBlockHeight = obtainCurMaxBlockHeightForRemote();

            // step4 获取监控的目标地址
            List<String> monitorContractAddressList = Optional.ofNullable(sysConfigService.getCacheValue(SysConfigConstant.MOTITOR_CONTRACT_ADDRESS_KEY))
                    .map(String::toLowerCase)
                    .map(str -> JSONUtil.toList(str, String.class))
                    .orElse(null);
            if (CollectionUtil.isEmpty(monitorContractAddressList)) {
                log.error("[SyncEventTask#asyncHandleTask] 监控的合约地址为空");
                sleep(TimeUnit.MINUTES, 1);
                continue;
            }

            // 同步交易数据
            syncTxData(monitorBlockHeight, blockHeightThreshold, curMaxBlockHeight, monitorContractAddressList);

            sleep(TimeUnit.SECONDS, 3);
        }
    }

    /**
     * 让当前线程睡眠指定时间
     *
     * @param timeUnit  单位
     * @param sleepTime 时长
     */
    private void sleep(TimeUnit timeUnit, Integer sleepTime) {
        try {
            timeUnit.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.error("[SyncEventTask#sleep] 调用睡眠方法出问题了", e);
        }
    }

    /**
     * 从远程获取区块链的最大高度
     *
     * @return
     */
    public Integer obtainCurMaxBlockHeightForRemote() {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put("jsonrpc", "2.0");
        paramMap.put("method", "eth_blockNumber");
        paramMap.put("params", Lists.newArrayList());
        paramMap.put("id", blockConfig.getBlockId());

        // resultStr 结果格式如下： {"jsonrpc":"2.0","id":"83","result":"0xc6638d"}
        final String resultStr = HttpUtils.postJson(blockConfig.getBlockReqHost(),
                JSONUtil.toJsonStr(paramMap));

        final Integer curMaxBlockHeight = Optional.ofNullable(resultStr).map(JSONUtil::parseObj)
                .map(jsonObj -> jsonObj.getStr("result"))
                .map(jsonStr -> jsonStr.substring(2))
                .map(jsonStr -> Integer.parseInt(jsonStr, 16)).orElse(-1);

        return curMaxBlockHeight;
    }

    /**
     * @param monitorBlockHeight         监控区块高度
     * @param blockHeightThreshold       设置的区块阈值
     * @param curMaxBlockHeight          区块链目前最大区块高度
     * @param monitorContractAddressList 监控的目标合约地址
     */
    private void syncTxData(Integer monitorBlockHeight, Integer blockHeightThreshold,
                            Integer curMaxBlockHeight, List<String> monitorContractAddressList) {

        while (monitorBlockHeight + blockHeightThreshold <= curMaxBlockHeight) {
            List<BlockTxInfo> blockTxInfoList = obtainBlockDataByBlockhHeightFromRemote(monitorBlockHeight);

            filterTxDataByMonitorAddress(blockTxInfoList, monitorContractAddressList);

            List<BlockTxDetailInfo> blockTxDetailInfoList = obtainBlockTxDetailInfoByHash(blockTxInfoList);
            encodeDataAndInsertDatabase(blockTxDetailInfoList);
            System.out.println(1);
        }
    }

    /**
     * 解码 topic和datad的数据，并将数据保存到数据库
     *
     * @param blockTxDetailInfoList
     */
    private void encodeDataAndInsertDatabase(List<BlockTxDetailInfo> blockTxDetailInfoList) {
        if (CollectionUtil.isEmpty(blockTxDetailInfoList)) {
            return;
        }

        blockTxDetailInfoList.forEach(blockTxDetailInfo -> {
            if (Objects.isNull(blockTxDetailInfo)) {
                return;
            }

            final String topic0 = Optional.of(blockTxDetailInfo)
                    .map(BlockTxDetailInfo::getLogs)
                    .map(list -> list.get(0))
                    .map(Logs::getTopics)
                    .map(topicList -> topicList.get(0))
                    .orElse(null);

            final FunctionEventParseService functionEventParseService = SpringUtil.getBean("functionEventParseReverseService", FunctionEventParseService.class);
            functionEventParseService.eventParse(blockTxDetailInfo);

        });


    }


    /**
     * 根据交易hash获取交易的详细信息
     *
     * @param blockTxInfoList
     * @return
     */
    private List<BlockTxDetailInfo> obtainBlockTxDetailInfoByHash(List<BlockTxInfo> blockTxInfoList) {
        if (CollectionUtil.isEmpty(blockTxInfoList)) {
            return null;
        }

        List<BlockTxDetailInfo> blockTxDetailInfoList = Lists.newArrayList();
        blockTxInfoList.forEach(blockTxInfo -> {
            String txDetailResultStr = obtainTxDetailFromRemote(blockTxInfo);

            BlockTxDetailInfo blockTxDetailInfo = parseTxDetailResultStr(txDetailResultStr);
            if (Objects.isNull(blockTxDetailInfo)) {
                return;
            }

            blockTxDetailInfoList.add(blockTxDetailInfo);
        });
        return blockTxDetailInfoList;
    }

    /**
     * 解析交易详细数据
     *
     * @param txDetailResultStr
     * @return
     */
    private BlockTxDetailInfo parseTxDetailResultStr(String txDetailResultStr) {
        BlockTxDetailInfo blockTxDetailInfo = Optional.ofNullable(txDetailResultStr)
                .map(JSONUtil::parseObj)
                .map(jsonObj -> jsonObj.getStr("result"))
                .map(str -> JSONUtil.toBean(str, BlockTxDetailInfo.class))
                .orElse(null);

        return blockTxDetailInfo;
    }


    private String obtainTxDetailFromRemote(BlockTxInfo blockTxInfo) {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put("jsonrpc", "2.0");
        paramMap.put("method", "eth_getTransactionReceipt");
        paramMap.put("params", Collections.singleton(blockTxInfo.getHash()));
        paramMap.put("id", 1);
        String txDetailResultStr = HttpUtils.postJson(blockConfig.getBlockReqHost(), JSONUtil.toJsonStr(paramMap));
        return txDetailResultStr;
    }

    /**
     * 过滤掉目的地址不是 我们监控的交易数据
     *
     * @param blockTxInfoList
     * @param monitorContractAddressList
     */
    private void filterTxDataByMonitorAddress(List<BlockTxInfo> blockTxInfoList, List<String> monitorContractAddressList) {
        if (CollectionUtil.isEmpty(blockTxInfoList) || CollectionUtil.isEmpty(monitorContractAddressList)) {
            return;
        }
        blockTxInfoList.removeIf(blockTxInfo -> StringUtils.isBlank(blockTxInfo.getTo()) || !monitorContractAddressList.contains(blockTxInfo.getTo().toLowerCase()));

    }

    /**
     * 获取监控块高数据
     *
     * @return
     */
    private List<BlockTxInfo> obtainBlockDataByBlockhHeightFromRemote(Integer monitorBlockHeight) {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put("jsonrpc", "2.0");
        paramMap.put("method", "eth_getBlockByNumber");
        List<Object> paramList = Lists.newArrayList();
        // 区块高度:0x开头的16进制,例如: 0xc67021
        paramList.add("0x" + Integer.toHexString(monitorBlockHeight));
        paramList.add(Boolean.TRUE);
        paramMap.put("params", paramList);
        paramMap.put("id", "1");

        String blockDataResultStr = HttpUtils.postJson(blockConfig.getBlockReqHost(), JSONUtil.toJsonStr(paramMap));

        List<BlockTxInfo> txInfoList = Optional.ofNullable(blockDataResultStr)
                .map(JSONUtil::parseObj)
                .map(jsonObj -> jsonObj.getJSONObject("result"))
                .map(jsonObj -> {
                    JSONArray jsonArray = jsonObj.getJSONArray("transactions");
                    List<BlockTxInfo> blockTxInfoList = Lists.newArrayList();
                    for (int i = 0; i < jsonArray.size(); i++) {
                        final JSONObject jsonObject = jsonArray.getJSONObject(i);
                        final String hash = jsonObject.getStr("hash");
                        final String to = jsonObject.getStr("to");
                        if (StringUtils.isBlank(to)) {
                            System.out.println(to);
                        }
                        final BlockTxInfo blockTxInfo = new BlockTxInfo();
                        blockTxInfo.setHash(hash);
                        blockTxInfo.setTo(to);
                        blockTxInfoList.add(blockTxInfo);
                    }
                    return blockTxInfoList;
                }).orElse(null);

        return txInfoList;
    }

    public static void main(String[] args) {
        String str = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"blockHash\":\"0x8fd56c71f00215b1eb4d33d1c580da1c943de78c19932ba86197785c11a91c4c\",\"blockNumber\":\"0xc6260b\",\"contractAddress\":null,\"cumulativeGasUsed\":\"0x4593d9\",\"effectiveGasPrice\":\"0x59682f07\",\"from\":\"0xe26f015ba6b8c400ce327ceebe34b717e6897e69\",\"gasUsed\":\"0x8d77\",\"logs\":[{\"address\":\"0x11a2fa9d918c7c7c0130e8a4cb2e4df7ec695e8f\",\"topics\":[\"0x68e1674423672999d6cb7284c1639330a54ee59c605137771e236db48a17d899\",\"0x000000000000000000000000e26f015ba6b8c400ce327ceebe34b717e6897e69\",\"0x000000000000000000000000e26f015ba6b8c400ce327ceebe34b717e6897e69\",\"0x860819867030ad6edc496114db7b3f56826c647eeadaba1d06929ea6750407e2\"],\"data\":\"0x00000000000000000000000000000000000000000000000000470de4df820000\",\"blockNumber\":\"0xc6260b\",\"transactionHash\":\"0x8691e89c2c984ed057d63f15b3a8742d46cb47a00deee3d91c638dcd48284b3a\",\"transactionIndex\":\"0xa\",\"blockHash\":\"0x8fd56c71f00215b1eb4d33d1c580da1c943de78c19932ba86197785c11a91c4c\",\"logIndex\":\"0x24\",\"removed\":false}],\"logsBloom\":\"0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000800002000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000004004000000020000000000000800000000000000000000000000000020000000000000000000000000000000000000000000000000000800000000000000020000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000\",\"status\":\"0x1\",\"to\":\"0x11a2fa9d918c7c7c0130e8a4cb2e4df7ec695e8f\",\"transactionHash\":\"0x8691e89c2c984ed057d63f15b3a8742d46cb47a00deee3d91c638dcd48284b3a\",\"transactionIndex\":\"0xa\",\"type\":\"0x2\"}}\n";
        final String result = Optional.of(str)
                .map(JSONUtil::parseObj)
                .map(jsonobj -> jsonobj.getStr("result")).orElse(null);

        System.out.println(result);
    }
}

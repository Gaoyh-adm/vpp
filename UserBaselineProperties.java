package com.vpp.platform.resource.baseline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 用户基线任务配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "vpp.resource.user-baseline")
public class UserBaselineProperties {

    /** 单次读取和计算的户号数量 */
    private int userBatchSize = 100;

    /** 单次写入TDengine的点数 */
    private int writeBatchSize = 5000;

    /** 单次写入MySQL的审计明细数 */
    private int detailWriteBatchSize = 1000;

    /** 单次执行的到期修正任务数 */
    private int correctionTaskBatchSize = 20;

    /** 生成和修正任务租约秒数；执行中由独立心跳续约 */
    private int correctionLeaseSeconds = 300;

    /** 算法版本 */
    private String calcVersion = "V1";

}

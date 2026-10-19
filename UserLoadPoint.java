package com.vpp.platform.resource.baseline.model;

import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** 从TDengine读取的用户负荷点。 */
@Data
public class UserLoadPoint {

    private Timestamp ts;
    private BigDecimal loadMw;
    private String srcType;
    private String consNo;
    private String dataDate;
}


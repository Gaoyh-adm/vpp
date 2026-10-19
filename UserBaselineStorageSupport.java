package com.vpp.platform.resource.baseline;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;

/** 用户基线精度、追踪码和TDengine子表名转换。 */
@Component
public class UserBaselineStorageSupport {

    private static final BigDecimal SCALE_FACTOR = new BigDecimal("1000000");

    public Long toScaledLong(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(6, RoundingMode.DOWN).multiply(SCALE_FACTOR).longValueExact();
    }

    public BigDecimal fromScaledLong(Long value) {
        return value == null ? null : BigDecimal.valueOf(value, 6);
    }

    public LocalDateTime slotTime(LocalDate dataDate, int slotNo) {
        if (slotNo < 1 || slotNo > UserBaselineConstants.POINTS_PER_DAY) {
            throw new IllegalArgumentException("点序号必须在1至96之间");
        }
        return LocalDateTime.of(dataDate, LocalTime.MIDNIGHT).plusMinutes(slotNo * 15L);
    }

    public int slotOf(LocalDate dataDate, LocalDateTime timestamp) {
        if (dataDate == null || timestamp == null) {
            return 0;
        }
        LocalDateTime dayStart = dataDate.atStartOfDay();
        long minutes = java.time.Duration.between(dayStart, timestamp).toMinutes();
        if (minutes == 0 && timestamp.toLocalDate().isAfter(dataDate)) {
            minutes = 24L * 60L;
        }
        if (minutes == 0 && timestamp.toLocalDate().equals(dataDate)) {
            return UserBaselineConstants.POINTS_PER_DAY;
        }
        if (minutes < 15 || minutes > 24L * 60L || minutes % 15 != 0) {
            return 0;
        }
        return (int) (minutes / 15);
    }

    public String tableName(String consNo) {
        String digest = sha256(consNo).substring(0, 24);
        return "user_baseline_" + digest;
    }

    public String traceCode(String consNo, LocalDate dataDate, int slotNo, String calcVersion) {
        return sha256(consNo + "|" + dataDate + "|" + slotNo + "|" + calcVersion);
    }

    public void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("user_baseline_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("用户基线TDengine子表名不合法");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }
}

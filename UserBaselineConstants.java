package com.vpp.platform.resource.baseline;

/** 用户基线固定业务值。 */
public final class UserBaselineConstants {

    public static final String DAY_WORKDAY = "WORKDAY";
    public static final String DAY_RESTDAY = "RESTDAY";
    public static final String DAY_HOLIDAY = "HOLIDAY";

    public static final String STATUS_NO_BASELINE = "NO_BASELINE";
    public static final String STATUS_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_CORRECTED = "CORRECTED";
    public static final String STATUS_UNCORRECTABLE = "UNCORRECTABLE";

    public static final String TASK_PENDING = "PENDING";
    public static final String TASK_PROCESSING = "PROCESSING";
    public static final String TASK_RETRY_WAIT = "RETRY_WAIT";
    public static final String TASK_SUCCESS = "SUCCESS";

    public static final int POINTS_PER_DAY = 96;
    public static final int WINDOW_POINTS = 16;

    private UserBaselineConstants() {
    }
}

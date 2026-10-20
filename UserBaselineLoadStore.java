package com.vpp.platform.resource.baseline;

import com.vpp.platform.resource.entity.UserBaselineLoad;
import com.vpp.platform.resource.repository.UserBaselineLoadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户基线TDengine结果存储。 */
@Service
@RequiredArgsConstructor
public class UserBaselineLoadStore {

    private final UserBaselineLoadMapper userBaselineLoadMapper;
    private final UserBaselineStorageSupport storageSupport;
    private final UserBaselineProperties properties;

    /** 在户号固定子表内按业务日期替换完整96点；只用于当天原始基线首次生成。 */
    public void replaceUserDate(String consNo, LocalDate dataDate, List<UserBaselineLoad> rows) {
        String tableName = storageSupport.tableName(consNo);
        storageSupport.validateTableName(tableName);
        userBaselineLoadMapper.ensureTable(tableName, consNo);
        userBaselineLoadMapper.dropByDate(tableName,
                java.sql.Timestamp.valueOf(dataDate.atStartOfDay()),
                java.sql.Timestamp.valueOf(dataDate.plusDays(1).atStartOfDay()));
        if (rows == null || rows.isEmpty()) {
            return;
        }
        rows.forEach(row -> {
            row.setTbname(tableName);
            row.setConsNo(consNo);
            row.setDataDate(dataDate.toString());
        });
        int batchSize = Math.max(1, properties.getWriteBatchSize());
        for (int start = 0; start < rows.size(); start += batchSize) {
            userBaselineLoadMapper.insert(rows.subList(start, Math.min(start + batchSize, rows.size())));
        }
    }

    /** 覆盖同一子表同一时间戳的完整结果行。 */
    public void upsertPoint(UserBaselineLoad row) {
        String tableName = storageSupport.tableName(row.getConsNo());
        storageSupport.validateTableName(tableName);
        userBaselineLoadMapper.ensureTable(tableName, row.getConsNo());
        row.setTbname(tableName);
        userBaselineLoadMapper.insert(row);
    }

    /** 分批覆盖多个户号同一时刻的完整结果行。 */
    public void upsertPoints(List<UserBaselineLoad> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, String> tables = new LinkedHashMap<>();
        rows.forEach(row -> {
            String tableName = storageSupport.tableName(row.getConsNo());
            storageSupport.validateTableName(tableName);
            tables.putIfAbsent(tableName, row.getConsNo());
            row.setTbname(tableName);
        });
        tables.forEach(userBaselineLoadMapper::ensureTable);
        int batchSize = Math.max(1, properties.getWriteBatchSize());
        for (int start = 0; start < rows.size(); start += batchSize) {
            userBaselineLoadMapper.insert(rows.subList(start, Math.min(start + batchSize, rows.size())));
        }
    }
}

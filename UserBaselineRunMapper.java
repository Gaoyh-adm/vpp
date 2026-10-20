package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/** 用户基线运行记录映射器。 */
@Repository
public interface UserBaselineRunMapper extends SuperMapper<UserBaselineRun> {

    @Insert("INSERT INTO ra_user_baseline_run(data_date,calc_version,status,total_user_count,"
            + "success_user_count,failed_user_count,snapshot_time,started_at,execution_owner,"
            + "lease_expire_time,heartbeat_time,deleted) VALUES(#{run.dataDate},#{run.calcVersion},"
            + "'RUNNING',#{run.totalUserCount},0,0,#{run.snapshotTime},NOW(3),#{owner},"
            + "TIMESTAMPADD(SECOND,#{leaseSeconds},NOW(3)),NOW(3),0)")
    @Options(useGeneratedKeys = true, keyProperty = "run.id")
    int insertRunning(@Param("run") UserBaselineRun run, @Param("owner") String owner,
                      @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE ra_user_baseline_run SET status='RUNNING',execution_owner=#{owner},"
            + "lease_expire_time=TIMESTAMPADD(SECOND,#{leaseSeconds},NOW(3)),heartbeat_time=NOW(3),"
            + "success_user_count=0,failed_user_count=0,finished_at=NULL,error_message=NULL,"
            + "last_modify_time=NOW(3) WHERE id=#{id} AND deleted=0 AND (status IN "
            + "('FAILED','PARTIAL_FAILED') OR (status='RUNNING' AND lease_expire_time<=NOW(3)))")
    int claimForProcessing(@Param("id") Long id, @Param("owner") String owner,
                           @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE ra_user_baseline_run SET lease_expire_time=TIMESTAMPADD(SECOND,#{leaseSeconds},NOW(3)),"
            + "heartbeat_time=NOW(3),last_modify_time=NOW(3) WHERE id=#{id} AND deleted=0 "
            + "AND status='RUNNING' AND execution_owner=#{owner} AND lease_expire_time>NOW(3)")
    int renewLease(@Param("id") Long id, @Param("owner") String owner,
                   @Param("leaseSeconds") int leaseSeconds);

    @Select("SELECT * FROM ra_user_baseline_run WHERE id=#{id} AND deleted=0 AND status='RUNNING' "
            + "AND execution_owner=#{owner} AND lease_expire_time>NOW(3) FOR UPDATE")
    UserBaselineRun selectOwnedActiveForUpdate(@Param("id") Long id, @Param("owner") String owner);

    @Update("UPDATE ra_user_baseline_run SET status=#{status},success_user_count=#{successCount},"
            + "failed_user_count=#{failedCount},finished_at=NOW(3),error_message=NULL,"
            + "lease_expire_time=NULL,heartbeat_time=NOW(3),last_modify_time=NOW(3) WHERE id=#{id} "
            + "AND deleted=0 AND status='RUNNING' AND execution_owner=#{owner} AND lease_expire_time>NOW(3)")
    int complete(@Param("id") Long id, @Param("owner") String owner, @Param("status") String status,
                 @Param("successCount") int successCount, @Param("failedCount") int failedCount);

    @Update("UPDATE ra_user_baseline_run SET status='FAILED',success_user_count=#{successCount},"
            + "failed_user_count=#{failedCount},finished_at=NOW(3),error_message=#{errorMessage},"
            + "execution_owner=NULL,lease_expire_time=NULL,last_modify_time=NOW(3) WHERE id=#{id} "
            + "AND deleted=0 AND status='RUNNING' AND execution_owner=#{owner}")
    int releaseForFailure(@Param("id") Long id, @Param("owner") String owner,
                          @Param("successCount") int successCount, @Param("failedCount") int failedCount,
                          @Param("errorMessage") String errorMessage);
}

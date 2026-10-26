package com.isoprecheck.repository;

import com.isoprecheck.entity.AuthorizationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 授權檢查紀錄 Repository
 */
@Repository
public interface AuthorizationLogRepository extends JpaRepository<AuthorizationLog, Long> {

    /**
     * 依建立時間由新到舊分頁查詢授權紀錄
     *
     * @param pageable 分頁參數
     * @return 授權紀錄分頁資料
     */
    Page<AuthorizationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
package com.spdb.migration;

import java.time.LocalDate;

/**
 * 交易码迁移使用的源库请求表分区访问策略。
 */
interface TranCodeSourcePartition {
    String requestTableRef(LocalDate date);

    boolean exists(LocalDate date);

    LocalDate earliestPartitionDate();
}

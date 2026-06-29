/**
 * API 类型定义
 */
declare namespace API {
  /** 统一响应结构 */
  interface ApiResponse<T> {
    code: number;
    message: string;
    data: T;
  }

  /** 当前用户（与后端 LoginResponse 对齐） */
  interface CurrentUser {
    id: number;
    username: string;
    realName: string;
    email: string;
    role: string;  // 逗号分隔的角色字符串，如 "ADMIN" 或 "ADMIN,DEVELOPER"
    token?: string;
  }

  /** 同步任务 */
  interface SyncTask {
    id: number;
    name: string;
    taskName?: string;
    description: string;
    taskType: string; // CDC | SQL | BATCH | cdc_sync | etl | materialized
    status: string; // draft | submitting | running | paused | failed | cancelled | finished | saving_point
    sourceDatasourceId: number;
    sourceDatasourceName?: string;
    sourceDatabase: string;
    sourceTable: string;
    targetDatabase: string;
    targetTable: string;
    flinkJarId?: number;
    parallelism: number;
    checkpointIntervalMs: number;
    savepointPath: string;
    savepointTriggerId?: string;
    flinkJobId?: string;
    config: string;
    remark: string;
    checkpointCount: number;
    submittedAt?: string;
    lastCheckpointTime?: string;
    createdAt: string;
    updatedAt: string;
    // Additional fields from backend entity
    taskName?: string;
    sourceConfigId?: number;
    targetConfigId?: number;
    flinkSql?: string;
    syncStrategy?: string;
    tableMappings?: string;
    creatorId?: number;
    checkpointInfo?: any;
    currentLagMs?: number;
    throughputQps?: number;
    lastErrorMsg?: string;
  }

  /** 任务状态详情 */
  interface TaskStatusInfo {
    taskId: number;
    status: string;
    flinkJobId?: string;
    savepointTriggerId?: string;
    checkpointCount: number;
    lastCheckpointTime?: string;
    errorMessage?: string;
  }

  /** 数据源配置（与后端 DatasourceConfig 实体对齐） */
  interface DatasourceConfig {
    id: number;
    creatorId: number;
    configName: string;
    dbType: string; // mysql | postgresql | paimon
    host: string;
    port: number;
    database: string;
    username: string;
    passwordEncrypted: string;
    extraParams: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 数仓表元数据 */
  interface DwhTableMeta {
    id: number;
    database: string;
    tableName: string;
    paimonDb?: string;
    paimonTable?: string;
    layer: string; // ods | dwd | dws | ads
    tableType: string;
    storageFormat: string;
    recordCount: number;
    totalSize: number;
    totalSizeBytes?: number;
    fileCount: number;
    lastModifiedTime: string;
    businessDesc: string;
    owner: string;
    createdAt: string;
    updatedAt: string;
    // Additional fields from backend entity
    partitionKeys?: string;
    primaryKeys?: string;
    snapshotCount?: number;
    latestSnapshotId?: number;
  }

  /** 数仓表列元数据 */
  interface DwhColumnMeta {
    id: number;
    tableId: number;
    tableMetaId?: number;
    columnName: string;
    columnType: string;
    nullable: boolean;
    isNullable?: boolean;
    comment: string;
    businessComment?: string;
    isPartitionKey: boolean;
    isPk?: boolean;
    ordinalPosition: number;
    sortOrder?: number;
    sourceColumn?: string;
    defaultValue?: string;
  }

  /** 查询结果 */
  interface QueryResult {
    columns: { name: string; type: string }[];
    rows: any[][];
    totalRows: number;
    rowCount?: number;
    executionTime: number;
    durationMs?: number;
    status?: string;
    errorMsg?: string;
  }

  /** 报表模板 */
  interface ReportTemplate {
    id: number;
    name: string;
    reportName?: string;
    description: string;
    sql: string;
    sqlQuery?: string;
    chartType: string;
    reportType?: string;
    config: string;
    createdBy: string;
    isPublished?: boolean;
    createdAt: string;
    updatedAt: string;
  }

  /** 告警规则 */
  interface AlertRule {
    id: number;
    name: string;
    description: string;
    alertType: string;
    condition: string;
    threshold: number;
    level: string; // info | warning | critical
    enabled: boolean;
    notifyChannels: string[];
    createdAt: string;
    updatedAt: string;
  }

  /** 告警记录 */
  interface AlertRecord {
    id: number;
    ruleId: number;
    ruleName: string;
    level: string;
    message: string;
    triggerValue: string;
    triggerTime: string;
    resolved: boolean;
    resolvedAt?: string;
    resolvedBy?: string;
  }

  /** 数据质量规则 */
  interface QualityRule {
    id: number;
    name: string;
    description: string;
    layer: string;
    ruleType: string; // completeness | accuracy | consistency | timeliness | validity | uniqueness
    expression: string;
    threshold: number;
    enabled: boolean;
    lastCheckTime?: string;
    lastCheckResult?: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 数据质量告警 */
  interface QualityAlert {
    id: number;
    ruleId: number;
    ruleName: string;
    level: string;
    message: string;
    actualValue: string;
    triggerTime: string;
    resolved: boolean;
    resolvedAt?: string;
  }

  /** 表映射（CDC 同步任务） */
  interface TableMapping {
    sourceTable: string;
    targetDb: string; // ods | dwd | dws | ads
    targetTable: string;
    syncMode: string; // full+incremental | incremental
  }

  /** 维护日志 */
  interface MaintenanceLog {
    id: number;
    tableId?: number;
    tableMetaId?: number;
    tableName?: string;
    operation: string; // compact | expire | clean
    triggerType: string; // manual | auto
    status: string;
    detail: string;
    operator: string;
    createdAt: string;
    // Additional fields from backend entity
    operationType?: string;
    triggerTypeStr?: string;
    strategy?: string;
    retainLast?: number;
    startedAt?: string;
    finishedAt?: string;
    durationMs?: number;
    paimonDb?: string;
    database?: string;
  }
}

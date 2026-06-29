import { request } from '@umijs/max';

const API_PREFIX = '/api/v1';

// Auth
export async function login(data: { username: string; password: string }) {
  return request<API.ApiResponse<{ token: string; user: API.CurrentUser }>>(
    `${API_PREFIX}/auth/login`,
    { method: 'POST', data },
  );
}

export async function getCurrentUser() {
  return request<API.ApiResponse<API.CurrentUser>>(
    `${API_PREFIX}/auth/current-user`,
  );
}

// Sync Tasks
export async function getSyncTasks(params?: { status?: string; taskType?: string; keyword?: string }) {
  return request<API.ApiResponse<API.SyncTask[]>>(`${API_PREFIX}/sync-tasks`, { params });
}

export async function getSyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}`);
}

export async function createSyncTask(data: any) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks`, { method: 'POST', data });
}

export async function startSyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/start`, { method: 'POST' });
}

export async function pauseSyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/pause`, { method: 'POST' });
}

export async function resumeSyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/resume`, { method: 'POST' });
}

export async function stopSyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/stop`, { method: 'POST' });
}

export async function deleteSyncTask(id: number) {
  return request<API.ApiResponse<void>>(`${API_PREFIX}/sync-tasks/${id}`, { method: 'DELETE' });
}

export async function retrySyncTask(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/retry`, { method: 'POST' });
}

export async function triggerSavepoint(id: number) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}/savepoint`, { method: 'POST' });
}

export async function updateSyncTask(id: number, data: any) {
  return request<API.ApiResponse<API.SyncTask>>(`${API_PREFIX}/sync-tasks/${id}`, { method: 'PUT', data });
}

export async function getSyncTaskStatus(id: number) {
  return request<API.ApiResponse<API.TaskStatusInfo>>(`${API_PREFIX}/sync-tasks/${id}/status`);
}

export async function getSyncTaskLogs(id: number, params?: { type?: string; lines?: number }) {
  return request<API.ApiResponse<{ logs: string; type: string; lines: number }>>(`${API_PREFIX}/sync-tasks/${id}/logs`, { params });
}

export async function syncAllTaskStatus() {
  return request<API.ApiResponse<number>>(`${API_PREFIX}/sync-tasks/sync-status`, { method: 'POST' });
}

// Datasources
export async function getDatasources(params?: { dbType?: string }) {
  return request<API.ApiResponse<API.DatasourceConfig[]>>(`${API_PREFIX}/datasources`, { params });
}

export async function createDatasource(data: any) {
  return request<API.ApiResponse<API.DatasourceConfig>>(`${API_PREFIX}/datasources`, { method: 'POST', data });
}

export async function updateDatasource(id: number, data: any) {
  return request<API.ApiResponse<API.DatasourceConfig>>(`${API_PREFIX}/datasources/${id}`, { method: 'PUT', data });
}

export async function deleteDatasource(id: number) {
  return request<API.ApiResponse<void>>(`${API_PREFIX}/datasources/${id}`, { method: 'DELETE' });
}

export async function testDatasourceConnection(id: number) {
  return request<API.ApiResponse<{ success: boolean; message: string; dbVersion: string }>>(
    `${API_PREFIX}/datasources/${id}/test-connection`,
  );
}

export async function getIntrospectTables(datasourceId: number) {
  return request<API.ApiResponse<string[]>>(`${API_PREFIX}/datasources/${datasourceId}/tables`);
}

export async function getIntrospectTable(datasourceId: number, tableName: string) {
  return request<API.ApiResponse<any>>(`${API_PREFIX}/datasources/${datasourceId}/tables/${tableName}`);
}

// Sync Task preview
export async function previewCdcSql(data: any) {
  return request<API.ApiResponse<{ sql: string }>>(`${API_PREFIX}/sync-tasks/preview-cdc-sql`, { method: 'POST', data });
}

// DWH Tables
export async function getDwhTables(params?: { layer?: string; database?: string; keyword?: string }) {
  return request<API.ApiResponse<API.DwhTableMeta[]>>(`${API_PREFIX}/dwh/tables`, { params });
}

export async function getDwhTableDetail(id: number) {
  return request<API.ApiResponse<API.DwhTableMeta>>(`${API_PREFIX}/dwh/tables/${id}`);
}

export async function getDwhTableColumns(id: number) {
  return request<API.ApiResponse<API.DwhColumnMeta[]>>(`${API_PREFIX}/dwh/tables/${id}/columns`);
}

export async function syncMetadataFromPaimon() {
  return request<API.ApiResponse<number>>(`${API_PREFIX}/dwh/sync-metadata`, { method: 'POST' });
}

export async function updateTableBusinessDesc(id: number, businessDesc: string) {
  return request<API.ApiResponse<API.DwhTableMeta>>(
    `${API_PREFIX}/dwh/tables/${id}/metadata`,
    { method: 'PUT', data: { businessDesc } },
  );
}

export async function triggerCompact(id: number, compactStrategy?: string) {
  return request(`${API_PREFIX}/dwh/tables/${id}/compact`, {
    method: 'POST',
    params: { compactStrategy },
  });
}

export async function triggerExpireSnapshots(id: number, retainLast?: number) {
  return request(`${API_PREFIX}/dwh/tables/${id}/expire-snapshots`, {
    method: 'POST',
    params: { retainLast },
  });
}

// Query
export async function executeQuery(data: { sql: string; maxRows?: number; timeoutSeconds?: number }) {
  return request<API.ApiResponse<API.QueryResult>>(`${API_PREFIX}/query/execute`, { method: 'POST', data });
}

export async function getQueryHistory() {
  return request<API.ApiResponse<any[]>>(`${API_PREFIX}/query/history`);
}

// Reports
export async function getReports() {
  return request<API.ApiResponse<API.ReportTemplate[]>>(`${API_PREFIX}/reports`);
}

export async function getReportData(id: number) {
  return request<API.ApiResponse<API.QueryResult>>(`${API_PREFIX}/reports/${id}/data`);
}

export async function createReport(data: any) {
  return request<API.ApiResponse<API.ReportTemplate>>(`${API_PREFIX}/reports`, { method: 'POST', data });
}

// Settings
export async function healthCheck() {
  return request<API.ApiResponse<any>>(`${API_PREFIX}/settings/health-check`);
}

export async function getFlinkClusterConfig() {
  return request<API.ApiResponse<any>>(`${API_PREFIX}/settings/flink-cluster`);
}

export async function updateFlinkClusterConfig(data: any) {
  return request<API.ApiResponse<any>>(`${API_PREFIX}/settings/flink-cluster`, { method: 'PUT', data });
}

// Alerts
export async function getAlertRules() {
  return request<API.ApiResponse<API.AlertRule[]>>(`${API_PREFIX}/alert/rules`);
}

export async function createAlertRule(data: any) {
  return request<API.ApiResponse<API.AlertRule>>(`${API_PREFIX}/alert/rules`, { method: 'POST', data });
}

export async function updateAlertRule(id: number, data: any) {
  return request<API.ApiResponse<API.AlertRule>>(`${API_PREFIX}/alert/rules/${id}`, { method: 'PUT', data });
}

export async function deleteAlertRule(id: number) {
  return request<API.ApiResponse<void>>(`${API_PREFIX}/alert/rules/${id}`, { method: 'DELETE' });
}

export async function toggleAlertRule(id: number, enabled: boolean) {
  return request<API.ApiResponse<void>>(`${API_PREFIX}/alert/rules/${id}/toggle`, { method: 'POST', data: { enabled } });
}

export async function getAlertRecords(params?: { level?: string; resolved?: boolean }) {
  return request<API.ApiResponse<API.AlertRecord[]>>(`${API_PREFIX}/alert/records`, { params });
}

export async function resolveAlertRecord(id: number) {
  return request<API.ApiResponse<void>>(`${API_PREFIX}/alert/records/${id}/resolve`, { method: 'POST' });
}

// Quality
export async function getQualityRules(params?: { layer?: string; ruleType?: string }) {
  return request<API.ApiResponse<API.QualityRule[]>>(`${API_PREFIX}/quality/rules`, { params });
}

export async function createQualityRule(data: any) {
  return request<API.ApiResponse<API.QualityRule>>(`${API_PREFIX}/quality/rules`, { method: 'POST', data });
}

export async function runQualityCheck(ruleId?: number) {
  return request<API.ApiResponse<number>>(`${API_PREFIX}/quality/run-check`, { method: 'POST', data: { ruleId } });
}

export async function getQualityAlerts(params?: { level?: string; resolved?: boolean }) {
  return request<API.ApiResponse<API.QualityAlert[]>>(`${API_PREFIX}/quality/alerts`, { params });
}

// Maintenance
export async function getMaintenanceLogs(params?: { operation?: string; triggerType?: string }) {
  return request<API.ApiResponse<API.MaintenanceLog[]>>(`${API_PREFIX}/dwh/maintenance/logs`, { params });
}

export async function batchCompact(data: { layer?: string; fileCountThreshold?: number }) {
  return request<API.ApiResponse<{ triggered: number }>>(`${API_PREFIX}/dwh/maintenance/batch-compact`, { method: 'POST', data });
}

export async function batchExpireSnapshots(data: { layer?: string; retainLast?: number }) {
  return request<API.ApiResponse<{ triggered: number }>>(`${API_PREFIX}/dwh/maintenance/batch-expire`, { method: 'POST', data });
}

export async function cleanOrphanFiles(tableId?: number) {
  return request(`${API_PREFIX}/dwh/maintenance/clean-orphan`, { method: 'POST', data: { tableId } });
}

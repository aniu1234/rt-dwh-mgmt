import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Select, Input, Button, Space, Table, message, Alert, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { executeQuery, exportQuery, getQueryHistory, cancelQuery, cancelQueryByRequestId } from '@/api';

const AdhocQuery: React.FC = () => {
  const [sql, setSql] = useState('');
  const [maxRows, setMaxRows] = useState(1000);
  const [result, setResult] = useState<API.QueryResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [historyId, setHistoryId] = useState<number>();
  const [requestId, setRequestId] = useState<string>();

  const { data: historyPageData, loading: historyLoading, run: loadHistory } = useRequest(getQueryHistory, { defaultParams: [{ page: 0, size: 20 }] });
  const historyPage = historyPageData as API.PageResult<any> | undefined;
  // 后端返回 Page<QueryHistory>（全局拦截器已解包 ApiResponse），实际数组在 .content
  const historyList = historyPage?.content || [];

  const handleExecute = async () => {
    if (!sql.trim()) {
      message.warning('请输入 SQL 语句');
      return;
    }
    try {
      setExecuting(true);
      const currentRequestId = `web_${Date.now()}`;
      setRequestId(currentRequestId);
      const queryResult = await executeQuery({ sql, maxRows, requestId: currentRequestId });
      setResult(queryResult);
      setHistoryId((queryResult as any).historyId);
      if (queryResult.status === 'success') {
        message.success(`查询成功，返回 ${queryResult.rowCount} 行，耗时 ${queryResult.durationMs}ms`);
      } else {
        message.error(`查询失败: ${queryResult.errorMsg || '未知错误'}`);
      }
    } catch (e) {
      message.error('查询执行异常');
    } finally { setExecuting(false); }
  };

  const handleCancel = async () => {
    if (!requestId && !historyId) return;
    try {
      if (requestId) await cancelQueryByRequestId(requestId);
      else await cancelQuery(historyId!);
      message.info('已请求取消查询');
    } catch { message.error('取消失败，查询可能已结束'); }
  };

  const handleExport = async () => {
    if (!sql.trim()) return message.warning('请输入 SQL 语句');
    try {
      const blob = await exportQuery({ sql, maxRows });
      const url = URL.createObjectURL(blob as Blob); const link = document.createElement('a');
      link.href = url; link.download = 'query-result.csv'; link.click(); URL.revokeObjectURL(url);
    } catch { message.error('导出失败'); }
  };

  return (
    <PageContainer>
      <Card title="SQL 编辑器">
        <Space style={{ marginBottom: 12 }}>
          <Select
            value="paimon-flink-sql"
            style={{ width: 220 }}
            options={[
              { label: 'Paimon (Flink SQL Gateway)', value: 'paimon-flink-sql' },
              { label: 'Paimon (JDBC/Hive)', value: 'paimon-jdbc' },
            ]}
          />
          <Input
            placeholder="最大返回行数"
            value={maxRows}
            onChange={(e) => setMaxRows(parseInt(e.target.value) || 1000)}
            style={{ width: 100 }}
          />
          <Button type="primary" loading={executing} onClick={handleExecute}>▶ 执行查询</Button>
          <Button danger disabled={!executing || (!requestId && !historyId)} onClick={handleCancel}>取消查询</Button>
          <Button type="primary" style={{ background: '#52c41a' }} onClick={handleExport}>导出 CSV</Button>
        </Space>
        <textarea
          value={sql}
          onChange={(e) => setSql(e.target.value)}
          style={{
            width: '100%',
            minHeight: 120,
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: 16,
            borderRadius: 8,
            fontFamily: 'Courier New, monospace',
            fontSize: 13,
            lineHeight: 1.6,
            border: 'none',
            resize: 'vertical',
          }}
          placeholder="输入 SQL 查询语句..."
        />
      </Card>

      {result && (
        <Card title={`查询结果（耗时 ${result.durationMs}ms · 返回 ${result.rowCount} 行）`}>
          {result.status !== 'success' && <Alert type="error" message={result.errorMsg || '查询失败'} showIcon style={{ marginBottom: 12 }} />}
          <Tag color={result.status === 'success' ? 'green' : result.status === 'cancelled' ? 'orange' : 'red'}>{result.status}</Tag>
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            <Table<Record<string, any>>
              dataSource={result.rows.map((row, i) => ({ key: i, ...Object.fromEntries(result.columns.map((column, j) => [column, row[j]])) }))}
              columns={result.columns.map((column) => ({ title: column, dataIndex: column, key: column }))}
              size="small"
              pagination={false}
            />
          </div>
        </Card>
      )}

      <Card title="查询历史">
        <Table
          dataSource={historyList}
          rowKey="id"
          size="small"
          loading={historyLoading}
          pagination={{
            total: historyPage?.totalElements ?? 0,
            pageSize: historyPage?.size ?? 20,
            current: (historyPage?.number ?? 0) + 1,
            showSizeChanger: true,
            onChange: (page, pageSize) => loadHistory({ page: page - 1, size: pageSize }),
          }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', key: 'time' },
            { title: 'SQL', dataIndex: 'sqlText', key: 'sql', ellipsis: true },
            { title: '行数', dataIndex: 'resultRowCount', key: 'rows', width: 80 },
            { title: '耗时', dataIndex: 'durationMs', key: 'duration', width: 80, render: (v) => `${v}ms` },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              width: 80,
              render: (v) => v === 'success' ? '✓ 成功' : v === 'cancelled' ? '⏹ 已取消' : v === 'running' ? '… 执行中' : '✗ 失败',
            },
          ]}
        />
      </Card>
    </PageContainer>
  );
};

export default AdhocQuery;

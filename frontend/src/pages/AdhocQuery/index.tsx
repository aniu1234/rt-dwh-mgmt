import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Select, Input, Button, Space, Table, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { executeQuery, getQueryHistory } from '@/api';

const AdhocQuery: React.FC = () => {
  const [sql, setSql] = useState('');
  const [maxRows, setMaxRows] = useState(1000);
  const [result, setResult] = useState<API.QueryResult | null>(null);

  const { data: historyPageData, loading: historyLoading } = useRequest(getQueryHistory);
  // 后端返回 Page<QueryHistory>（全局拦截器已解包 ApiResponse），实际数组在 .content
  const historyList = Array.isArray(historyPageData?.content)
    ? historyPageData.content
    : Array.isArray(historyPageData)
      ? historyPageData
      : [];

  const handleExecute = async () => {
    if (!sql.trim()) {
      message.warning('请输入 SQL 语句');
      return;
    }
    try {
      const queryResult = await executeQuery({ sql, maxRows });
      setResult(queryResult);
      if (queryResult.totalRows !== undefined) {
        message.success(`查询成功，返回 ${queryResult.totalRows} 行，耗时 ${queryResult.executionTime}ms`);
      } else if (queryResult.status === 'failed') {
        message.error(`查询失败: ${queryResult.errorMsg || '未知错误'}`);
      }
    } catch (e) {
      message.error('查询执行异常');
    }
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
          <Button type="primary" onClick={handleExecute}>▶ 执行查询</Button>
          <Button type="primary" style={{ background: '#52c41a' }}>导出 CSV</Button>
          <Button type="primary" style={{ background: '#52c41a' }}>导出 Excel</Button>
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
        <Card title={`查询结果（耗时 ${result.executionTime}ms · 返回 ${result.totalRows} 行）`}>
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            <Table
              dataSource={result.rows.map((row, i) => ({ key: i, ...Object.fromEntries(result.columns.map((c, j) => [c.name, row[j]])) }))}
              columns={result.columns.map((c) => ({ title: c.name, dataIndex: c.name, key: c.name }))}
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
            total: historyPageData?.totalElements ?? 0,
            pageSize: historyPageData?.size ?? 20,
            current: (historyPageData?.number ?? 0) + 1,
            showSizeChanger: true,
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
              render: (v) => v === 'success' ? '✓ Success' : '✗ Failed',
            },
          ]}
        />
      </Card>
    </PageContainer>
  );
};

export default AdhocQuery;

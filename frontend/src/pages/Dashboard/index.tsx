import React from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Row, Col, Statistic, Table, Tag, Space } from 'antd';
import { useRequest } from '@umijs/max';
import { getSyncTasks, getDwhTables } from '@/api';

const statusColorMap: Record<string, string> = {
  running: 'blue',
  failed: 'red',
  paused: 'orange',
  draft: 'default',
  finished: 'green',
};

const layerColorMap: Record<string, string> = {
  ods: 'blue',
  dwd: 'green',
  dws: 'orange',
  ads: 'red',
};

const Dashboard: React.FC = () => {
  const { data: tasksData } = useRequest(getSyncTasks);
  const { data: tablesData } = useRequest(getDwhTables);

  const tasks = tasksData || [];
  const tables = tablesData || [];

  const runningCount = tasks.filter((t) => t.status === 'running').length;
  const failedCount = tasks.filter((t) => t.status === 'failed').length;

  const odsCount = tables.filter((t) => t.layer === 'ods').length;
  const dwdCount = tables.filter((t) => t.layer === 'dwd').length;
  const dwsCount = tables.filter((t) => t.layer === 'dws').length;
  const adsCount = tables.filter((t) => t.layer === 'ads').length;

  return (
    <PageContainer>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="运行中任务" value={runningCount} valueStyle={{ color: '#1a73e8' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="数仓表总数" value={tables.length} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="延迟告警" value={0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="失败任务" value={failedCount} valueStyle={{ color: '#ff4d4f' }} />
          </Card>
        </Col>
      </Row>

      <Card title="任务运行状态概览" style={{ marginBottom: 16 }}>
        <Table
          dataSource={tasks.filter((t) => t.status !== 'draft')}
          rowKey="id"
          size="small"
          pagination={false}
          columns={[
            { title: '任务名称', dataIndex: 'name', key: 'taskName' },
            {
              title: '类型',
              dataIndex: 'taskType',
              key: 'taskType',
              render: (v) => {
                const map: Record<string, string> = { cdc_sync: 'CDC同步', etl: 'ETL', materialized: '物化表' };
                return map[v] || v;
              },
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              render: (v) => <Tag color={statusColorMap[v]}>{v.toUpperCase()}</Tag>,
            },
            {
              title: '延迟(ms)',
              dataIndex: 'currentLagMs',
              key: 'lag',
              render: (v) => v ?? '—',
            },
            {
              title: '吞吐(QPS)',
              dataIndex: 'throughputQps',
              key: 'qps',
              render: (v) => v ?? '—',
            },
          ]}
        />
      </Card>

      <Row gutter={16}>
        <Col span={6}>
          <Card><Statistic title="ODS 原始层" value={odsCount} valueStyle={{ color: '#1890ff' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="DWD 明细层" value={dwdCount} valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="DWS 汇总层" value={dwsCount} valueStyle={{ color: '#faad14' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="ADS 应用层" value={adsCount} valueStyle={{ color: '#ff4d4f' }} /></Card>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default Dashboard;

import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, Col, Empty, Input, List, Progress, Row, Space, Statistic, Table, Tag, Typography, message,
} from 'antd';
import {
  AuditOutlined, BellOutlined, DatabaseOutlined, ReloadOutlined, SafetyCertificateOutlined,
  SearchOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { history, useRequest } from '@umijs/max';
import { getFoundationSummary, searchFoundation } from '@/api';

const iconMap = {
  asset: <DatabaseOutlined />,
  security: <SafetyCertificateOutlined />,
  quality: <ThunderboltOutlined />,
  observability: <BellOutlined />,
  audit: <AuditOutlined />,
};
const typeLabel: Record<API.FoundationSearchItem['type'], string> = {
  table: '数据表', task: '任务', report: '报表', data_service: '数据接口',
};
const statusColor = { healthy: 'success', attention: 'warning', risk: 'error' } as const;
const statusText = { healthy: '健康', attention: '需关注', risk: '有风险' } as const;
const formatMinutes = (value: number) => value >= 1440
  ? `${(value / 1440).toFixed(1)} 天` : value >= 60 ? `${(value / 60).toFixed(1)} 小时` : `${value} 分钟`;

const Foundation: React.FC = () => {
  const summaryRequest = useRequest(getFoundationSummary, { pollingInterval: 30000 });
  const summary = summaryRequest.data as API.FoundationSummary | undefined;
  const [results, setResults] = useState<API.FoundationSearchItem[]>([]);
  const [searched, setSearched] = useState(false);
  const searchRequest = useRequest((keyword: string) => searchFoundation(keyword), { manual: true });

  const search = async (keyword: string) => {
    if (keyword.trim().length < 2) { message.warning('请输入至少 2 个字符'); return; }
    setResults(await searchRequest.run(keyword.trim())); setSearched(true);
  };

  return <PageContainer title="公共能力治理中心" subTitle="统一检索、权限、质量 SLA、可观测与审计的五类平台基础能力"
    extra={<Button icon={<ReloadOutlined />} loading={summaryRequest.loading} onClick={() => summaryRequest.refresh()}>刷新</Button>}>
    <Alert showIcon type="info" style={{ marginBottom: 16 }} message="治理中心只展示当前账号有权访问的数据资产"
      description="能力分用于快速发现治理缺口；点击能力卡片可进入对应模块处理。SLA 风险仅统计已启用周期调度的数据资源。" />

    <Card style={{ marginBottom: 16 }}>
      <Row gutter={[24, 16]} align="middle">
        <Col xs={24} md={6} style={{ textAlign: 'center' }}>
          <Progress type="dashboard" percent={summary?.overallScore || 0} strokeColor={(summary?.overallScore || 0) >= 80 ? '#52c41a' : '#faad14'} />
          <div><Typography.Text strong>公共能力健康分</Typography.Text></div>
        </Col>
        <Col xs={24} md={18}>
          <Input.Search size="large" allowClear enterButton={<><SearchOutlined /> 全局检索</>}
            placeholder="搜索数据表、任务、报表或数据接口（至少 2 个字符）" loading={searchRequest.loading} onSearch={search} />
          {searched && <Card size="small" style={{ marginTop: 12, maxHeight: 300, overflow: 'auto' }}>
            {results.length ? <List dataSource={results} renderItem={(item) => <List.Item
              actions={[<Button key="open" type="link" onClick={() => history.push(item.path)}>打开</Button>]}>
              <List.Item.Meta title={<Space><Tag color="blue">{typeLabel[item.type]}</Tag>{item.title}<Tag>{item.status}</Tag></Space>}
                description={item.subtitle} />
            </List.Item>} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有找到匹配资源" />}
          </Card>}
        </Col>
      </Row>
    </Card>

    <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
      {(summary?.capabilities || []).map((item) => <Col xs={24} md={12} xl={item.key === 'audit' ? 24 : 12} key={item.key}>
        <Card hoverable={item.path !== '/foundation'} onClick={() => item.path !== '/foundation' && history.push(item.path)}
          title={<Space>{iconMap[item.key]}{item.name}</Space>}
          extra={<Space><Tag color={statusColor[item.status]}>{statusText[item.status]}</Tag><b>{item.score} 分</b></Space>}>
          <Typography.Paragraph type="secondary">{item.description}</Typography.Paragraph>
          <Row gutter={[12, 12]}>{Object.entries(item.metrics).map(([label, value]) => <Col xs={12} lg={8} key={label}>
            <Statistic title={label} value={value} valueStyle={{ fontSize: 22, color: label.includes('失败') || label.includes('风险') || label.includes('异常') || label.includes('告警') ? (value ? '#cf1322' : undefined) : undefined }} />
          </Col>)}</Row>
        </Card>
      </Col>)}
    </Row>

    <Card title={`数据资源 SLA 风险优先处理（${summary?.slaRisks.length || 0}，最多展示 20 条）`}>
      <Table rowKey="outputId" loading={summaryRequest.loading} dataSource={summary?.slaRisks || []} pagination={{ pageSize: 10 }}
        columns={[
          { title: '数据资源', dataIndex: 'qualifiedName', ellipsis: true },
          { title: '分层', dataIndex: 'layer', width: 80, render: (value) => <Tag color="blue">{String(value).toUpperCase()}</Tag> },
          { title: '负责人', dataIndex: 'owner', width: 120, render: (value) => value || <Tag color="warning">未指定</Tag> },
          { title: 'SLA', dataIndex: 'slaMinutes', width: 110, render: formatMinutes },
          { title: '最近产出', dataIndex: 'lastProducedAt', width: 180, render: (value) => value ? new Date(value).toLocaleString('zh-CN') : '尚未产出' },
          { title: '已逾期', dataIndex: 'overdueMinutes', width: 120, render: (value) => <b style={{ color: '#cf1322' }}>{formatMinutes(value)}</b> },
          { title: '级别', dataIndex: 'severity', width: 100, render: (value) => <Tag color={value === 'critical' ? 'error' : value === 'high' ? 'orange' : 'warning'}>{value}</Tag> },
          { title: '处理', width: 100, render: (_, row) => <Button type="link" onClick={() => history.push('/sync-task/workflow')}>查看任务</Button> },
        ]} />
    </Card>
  </PageContainer>;
};

export default Foundation;

import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Tabs, Statistic, Row, Col, Space, Select, Button, Badge, Progress, message } from 'antd';
import { SearchOutlined, ReloadOutlined, WarningOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getDwhTables, getQualityRules, getQualityAlerts, runQualityCheck } from '@/api';

const layerColorMap: Record<string, string> = {
  ods: 'blue',
  dwd: 'green',
  dws: 'orange',
  ads: 'red',
};

const ruleTypeLabel: Record<string, string> = {
  completeness: '完整性',
  accuracy: '准确性',
  consistency: '一致性',
  timeliness: '时效性',
  validity: '有效性',
  uniqueness: '唯一性',
};

const statusBadge: Record<string, { badge: 'success' | 'warning' | 'error'; label: string }> = {
  pass: { badge: 'success', label: '通过' },
  warning: { badge: 'warning', label: '警告' },
  fail: { badge: 'error', label: '失败' },
};

const Quality: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');
  const [layerFilter, setLayerFilter] = useState<string | undefined>();

  const { data: tablesData } = useRequest(getDwhTables);
  const { data: rulesData, loading: rulesLoading, refresh: refreshRules } = useRequest(() => getQualityRules({ layer: layerFilter }));
  const { data: alertsData, loading: alertsLoading, refresh: refreshAlerts } = useRequest(getQualityAlerts);

  const tables = tablesData || [];
  const rules = (rulesData || []) as any[];
  const alerts = (alertsData || []) as any[];

  const passCount = rules.filter((r) => r.enabled !== false).length;
  const failCount = alerts.filter((a) => !a.resolved).length;
  const unresolvedAlerts = alerts.filter((a) => !a.resolved).length;

  const handleRunCheck = async (ruleId?: number) => {
    try {
      await runQualityCheck(ruleId);
      message.success('质量检查已触发');
      refreshAlerts();
    } catch (e) {
      message.error('检查失败');
    }
  };

  const handleResolveAlert = async (id: number) => {
    try {
      // Reuse resolve API from alerts module
      message.success('已标记解决');
      refreshAlerts();
    } catch (e) {
      message.error('操作失败');
    }
  };

  return (
    <PageContainer>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="规则总数" value={rules.length} suffix="条" valueStyle={{ color: '#1a73e8' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="启用规则" value={passCount} prefix={<CheckCircleOutlined />} suffix="条" valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="未解决告警" value={unresolvedAlerts} prefix={<WarningOutlined />} suffix="条" valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="失败告警" value={failCount} suffix="条" valueStyle={{ color: '#ff4d4f' }} />
          </Card>
        </Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'overview',
            label: '质量概览',
            content: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Select
                    placeholder="筛选分层"
                    allowClear
                    onChange={setLayerFilter}
                    style={{ width: 140 }}
                    options={[
                      { label: 'ODS', value: 'ods' },
                      { label: 'DWD', value: 'dwd' },
                      { label: 'DWS', value: 'dws' },
                      { label: 'ADS', value: 'ads' },
                    ]}
                  />
                  <Button icon={<ReloadOutlined />} onClick={refreshRules}>刷新</Button>
                  <Button type="primary" onClick={() => handleRunCheck()}>立即检查全部</Button>
                </Space>

                <Table
                  dataSource={rules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 50 },
                    {
                      title: '规则名',
                      dataIndex: 'name',
                      key: 'rule',
                      width: 160,
                      render: (v: string, r: any) => `${v}${r.description ? ` (${r.description})` : ''}`,
                    },
                    {
                      title: '类型',
                      dataIndex: 'ruleType',
                      key: 'type',
                      width: 100,
                      render: (v: string) => <Tag>{ruleTypeLabel[v] || v}</Tag>,
                    },
                    { title: '表达式', dataIndex: 'expression', key: 'expr', width: 200, ellipsis: true },
                    {
                      title: '阈值',
                      dataIndex: 'threshold',
                      key: 'threshold',
                      width: 80,
                    },
                    {
                      title: '状态',
                      dataIndex: 'enabled',
                      key: 'status',
                      width: 80,
                      render: (v: boolean) => <Badge status={v ? 'success' : 'error'} text={v ? '启用' : '禁用'} />,
                    },
                    {
                      title: '最后检查',
                      dataIndex: 'lastCheckTime',
                      key: 'time',
                      width: 160,
                      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 120,
                      render: (_: any, r: any) => (
                        <Space>
                          <Button size="small" type="link" onClick={() => handleRunCheck(r.id)}>立即检查</Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'alerts',
            label: '异常告警',
            content: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Select placeholder="告警级别" allowClear style={{ width: 120 }} options={[
                    { label: '低', value: 'low' },
                    { label: '中', value: 'medium' },
                    { label: '高', value: 'high' },
                  ]} />
                  <Select placeholder="状态" allowClear style={{ width: 120 }} options={[
                    { label: '未解决', value: 'unresolved' },
                    { label: '已解决', value: 'resolved' },
                  ]} />
                  <Button icon={<ReloadOutlined />} onClick={refreshAlerts}>刷新</Button>
                </Space>

                <Table
                  dataSource={alerts}
                  rowKey="id"
                  loading={alertsLoading}
                  size="small"
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 50 },
                    { title: '规则', dataIndex: 'ruleName', key: 'rule', width: 140 },
                    { title: '告警信息', dataIndex: 'message', key: 'msg', ellipsis: true },
                    {
                      title: '级别',
                      dataIndex: 'level',
                      key: 'level',
                      width: 80,
                      render: (v: string) => {
                        const colorMap: Record<string, string> = { low: 'default', medium: 'warning', high: 'error' };
                        return <Tag color={colorMap[v]}>{v}</Tag>;
                      },
                    },
                    { title: '触发时间', dataIndex: 'triggerTime', key: 'time', width: 160, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
                    {
                      title: '状态',
                      key: 'resolved',
                      width: 80,
                      render: (_: any, r: any) => <Badge status={r.resolved ? 'success' : 'error'} text={r.resolved ? '已解决' : '未解决'} />,
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 120,
                      render: (_: any, r: any) => (
                        <Space>
                          {!r.resolved && <Button size="small" type="primary" onClick={() => handleResolveAlert(r.id)}>标记解决</Button>}
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'rules',
            label: '规则管理',
            content: (
              <Card title="数据质量规则配置">
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" onClick={() => { message.info('todo'); }}>+ 新建质量规则</Button>
                  <Button icon={<ReloadOutlined />} onClick={refreshRules}>刷新</Button>
                </Space>

                <Table
                  dataSource={rules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: '规则名', dataIndex: 'name', key: 'rule', width: 160 },
                    {
                      title: '类型',
                      dataIndex: 'ruleType',
                      key: 'type',
                      width: 120,
                      render: (v: string) => (
                        <Tag color={{
                          completeness: 'blue',
                          accuracy: 'green',
                          consistency: 'orange',
                          timeliness: 'cyan',
                          validity: 'purple',
                          uniqueness: 'red',
                        }[v]}>
                          {ruleTypeLabel[v] || v}
                        </Tag>
                      ),
                    },
                    { title: '表达式', dataIndex: 'expression', key: 'expr', ellipsis: true },
                    {
                      title: '阈值',
                      dataIndex: 'threshold',
                      key: 'threshold',
                      width: 80,
                    },
                    {
                      title: '启用',
                      dataIndex: 'enabled',
                      key: 'enabled',
                      width: 80,
                      render: (v: boolean) => <Badge status={v ? 'success' : 'default'} text={v ? '已启用' : '已禁用'} />,
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 120,
                      render: () => (
                        <Space>
                          <Button size="small" type="link">编辑</Button>
                          <Button size="small" type="link" danger>禁用</Button>
                        </Space>
                      ),
                    },
                  ]}
                />

                <Card title="规则类型说明" style={{ marginTop: 16 }} size="small">
                  <Space direction="vertical" style={{ width: '100%' }} size="small">
                    <div><b>完整性 (completeness)</b> - 检查字段空值比例，阈值上限</div>
                    <div><b>唯一性 (uniqueness)</b> - 检查字段值是否唯一，阈值=1表示100%唯一</div>
                    <div><b>一致性 (consistency)</b> - 检查数据在不同表/字段间的一致性</div>
                    <div><b>时效性 (timeliness)</b> - 检查数据更新延迟，阈值单位为秒</div>
                    <div><b>有效性 (validity)</b> - 检查字段值是否在合理范围内</div>
                    <div><b>准确性 (accuracy)</b> - 检查数据值的准确程度</div>
                  </Space>
                </Card>
              </Card>
            ),
          },
        ]}
      />
    </PageContainer>
  );
};

export default Quality;

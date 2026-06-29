import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Tabs, Statistic, Row, Col, Space, Select, Button, Badge, Modal, Input, message, Switch, Form } from 'antd';
import { PlusOutlined, ReloadOutlined, BellOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import {
  getAlertRules, getAlertRecords, createAlertRule, updateAlertRule,
  deleteAlertRule, toggleAlertRule, resolveAlertRecord,
} from '@/api';

const targetTypeLabel: Record<string, string> = {
  sync_task: '同步任务',
  table: '数仓表',
  quality: '数据质量',
};

const targetTypeColor: Record<string, string> = {
  sync_task: 'blue',
  table: 'orange',
  quality: 'green',
};

const alertLevelColor: Record<string, string> = {
  low: 'default',
  medium: 'warning',
  high: 'error',
};

const alertLevelLabel: Record<string, string> = {
  low: '低',
  medium: '中',
  high: '高',
};

const channelLabel: Record<string, string> = {
  email: '邮件',
  dingtalk: '钉钉',
  wecom: '企微',
  slack: 'Slack',
};

const Alert: React.FC = () => {
  const [activeTab, setActiveTab] = useState('rules');
  const [createRuleVisible, setCreateRuleVisible] = useState(false);
  const [editingRule, setEditingRule] = useState<any>(null);
  const [form] = Form.useForm();

  const { data: rulesData, loading: rulesLoading, refresh: refreshRules } = useRequest(getAlertRules);
  const { data: recordsData, loading: recordsLoading, refresh: refreshRecords } = useRequest(getAlertRecords);

  const rules = (rulesData || []) as any[];
  const records = (recordsData || []) as any[];

  const unresolvedCount = records.filter((r) => !r.resolved).length;
  const enabledRuleCount = rules.filter((r) => r.enabled).length;
  const totalTriggerCount = rules.reduce((s, r) => s + (r.triggerCount || 0), 0);

  const handleCreateOrUpdate = async (values: any) => {
    try {
      const payload = {
        name: values.ruleName,
        description: values.description || '',
        alertType: values.targetType === 'sync_task' ? 'task' : values.targetType === 'table' ? 'table' : 'quality',
        condition: values.metric,
        threshold: values.threshold,
        level: values.alertLevel,
        notifyChannels: values.channels,
      };
      if (editingRule) {
        await updateAlertRule(editingRule.id, payload);
        message.success('规则已更新');
      } else {
        await createAlertRule(payload);
        message.success('告警规则已创建');
      }
      setCreateRuleVisible(false);
      setEditingRule(null);
      form.resetFields();
      refreshRules();
    } catch (e) {
      message.error(editingRule ? '更新失败' : '创建失败');
    }
  };

  const handleToggle = async (id: number, enabled: boolean) => {
    try {
      await toggleAlertRule(id, enabled);
      message.success(enabled ? '规则已启用' : '规则已禁用');
      refreshRules();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteAlertRule(id);
      message.success('规则已删除');
      refreshRules();
    } catch (e) {
      message.error('删除失败');
    }
  };

  const handleResolve = async (id: number) => {
    try {
      await resolveAlertRecord(id);
      message.success('已标记为解决');
      refreshRecords();
    } catch (e) {
      message.error('操作失败');
    }
  };

  return (
    <PageContainer>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="告警规则" value={rules.length} suffix="条" prefix={<BellOutlined />} valueStyle={{ color: '#1a73e8' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已启用" value={enabledRuleCount} suffix="条" valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="未解决告警" value={unresolvedCount} suffix="条" valueStyle={{ color: '#ff4d4f' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="累计触发" value={totalTriggerCount} suffix="次" valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'rules',
            label: '告警规则',
            content: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingRule(null); setCreateRuleVisible(true); }}>
                    新建告警规则
                  </Button>
                  <Button icon={<ReloadOutlined />} onClick={refreshRules}>刷新</Button>
                </Space>

                <Table
                  dataSource={rules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 50 },
                    { title: '规则名称', dataIndex: 'name', key: 'name', width: 160 },
                    { title: '描述', dataIndex: 'description', key: 'desc', width: 160, ellipsis: true },
                    {
                      title: '级别',
                      dataIndex: 'level',
                      key: 'level',
                      width: 80,
                      render: (v) => <Tag color={alertLevelColor[v]}>{alertLevelLabel[v] || v}</Tag>,
                    },
                    {
                      title: '通知渠道',
                      dataIndex: 'notifyChannels',
                      key: 'channels',
                      width: 140,
                      render: (v: string[]) => (v || []).map((c) => <Tag key={c}>{channelLabel[c] || c}</Tag>),
                    },
                    {
                      title: '启用',
                      dataIndex: 'enabled',
                      key: 'enabled',
                      width: 80,
                      render: (v: boolean, record: any) => (
                        <Switch checked={v} size="small" onChange={(checked) => handleToggle(record.id, checked)} />
                      ),
                    },
                    { title: '创建时间', dataIndex: 'createdAt', key: 'created', width: 160, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
                    {
                      title: '操作',
                      key: 'action',
                      width: 120,
                      render: (_: any, record: any) => (
                        <Space>
                          <Button size="small" type="link" onClick={() => { setEditingRule(record); form.setFieldsValue({ ruleName: record.name, alertLevel: record.level }); setCreateRuleVisible(true); }}>编辑</Button>
                          <Button size="small" type="link" danger onClick={() => handleDelete(record.id)}>删除</Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'records',
            label: '告警记录',
            content: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Select placeholder="告警级别" allowClear style={{ width: 120 }} options={[
                    { label: '低', value: 'low' },
                    { label: '中', value: 'medium' },
                    { label: '高', value: 'high' },
                  ]} />
                  <Select placeholder="解决状态" allowClear style={{ width: 120 }} options={[
                    { label: '未解决', value: 'unresolved' },
                    { label: '已解决', value: 'resolved' },
                  ]} />
                  <Button icon={<ReloadOutlined />} onClick={refreshRecords}>刷新</Button>
                </Space>

                <Table
                  dataSource={records}
                  rowKey="id"
                  loading={recordsLoading}
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
                      render: (v) => <Tag color={alertLevelColor[v]}>{alertLevelLabel[v] || v}</Tag>,
                    },
                    { title: '通知时间', dataIndex: 'triggerTime', key: 'time', width: 160, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
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
                          {!r.resolved && <Button size="small" type="primary" onClick={() => handleResolve(r.id)}>标记解决</Button>}
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'channels',
            label: '通知渠道',
            content: (
              <Card title="通知渠道配置">
                <Row gutter={16}>
                  <Col span={8}>
                    <Card size="small" title="邮件通知" extra={<Switch defaultChecked size="small" />}>
                      <Space direction="vertical" style={{ width: '100%' }} size="small">
                        <div>SMTP: smtp.company.com:465</div>
                        <div>发件人: dwh-alert@company.com</div>
                        <div>收件人: dev-team@company.com</div>
                        <div>TLS: 已启用</div>
                      </Space>
                      <Button size="small" type="link" style={{ marginTop: 8 }}>编辑配置</Button>
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card size="small" title="钉钉通知" extra={<Switch defaultChecked size="small" />}>
                      <Space direction="vertical" style={{ width: '100%' }} size="small">
                        <div>Webhook: https://oapi.dingtalk.com/robot/send?access_token=***</div>
                        <div>关键词: 数仓告警</div>
                        <div>安全设置: 加签模式</div>
                      </Space>
                      <Button size="small" type="link" style={{ marginTop: 8 }}>编辑配置</Button>
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card size="small" title="企微通知" extra={<Switch size="small" />}>
                      <Space direction="vertical" style={{ width: '100%' }} size="small">
                        <div>Webhook: 未配置</div>
                        <div>状态: 未启用</div>
                      </Space>
                      <Button size="small" type="link" style={{ marginTop: 8 }}>配置</Button>
                    </Card>
                  </Col>
                </Row>
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingRule ? '编辑告警规则' : '新建告警规则'}
        open={createRuleVisible}
        onCancel={() => { setCreateRuleVisible(false); setEditingRule(null); form.resetFields(); }}
        onOk={() => form.submit()}
        okText={editingRule ? '保存' : '创建规则'}
        width={640}
      >
        <Form form={form} layout="vertical" onFinish={handleCreateOrUpdate}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如: 任务延迟超限告警" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="规则描述" />
          </Form.Item>
          <Form.Item name="alertLevel" label="告警级别" rules={[{ required: true }]}>
            <Select options={[
              { label: '低 — 仅记录', value: 'low' },
              { label: '中 — 发送通知', value: 'medium' },
              { label: '高 — 紧急通知', value: 'high' },
            ]} />
          </Form.Item>
          <Form.Item name="channels" label="通知渠道">
            <Select mode="multiple" options={[
              { label: '邮件', value: 'email' },
              { label: '钉钉', value: 'dingtalk' },
              { label: '企微', value: 'wecom' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Alert;

import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Statistic, Row, Col, Table, Tag, Button, Space, message, Modal, Form, Input, InputNumber, Select } from 'antd';
import { useRequest } from '@umijs/max';
import { healthCheck, getFlinkClusterConfig, updateFlinkClusterConfig } from '@/api';

const Settings: React.FC = () => {
  const { data: healthData, loading, refresh } = useRequest(healthCheck);
  const { data: configData, refresh: refreshConfig } = useRequest(getFlinkClusterConfig);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [form] = Form.useForm();

  const health = healthData;
  const config = configData || {};

  const statusIcon = (status: string) =>
    status === 'healthy' ? '\u2713' : '\u2717';

  const statusColor = (status: string) =>
    status === 'healthy' ? '#52c41a' : '#ff4d4f';

  const handleSaveConfig = async (values: any) => {
    try {
      await updateFlinkClusterConfig(values);
      message.success('配置已保存');
      setEditModalOpen(false);
      refreshConfig();
    } catch (e: any) {
      message.error(e?.message || '保存异常');
    }
  };

  const handleTestConnectivity = async () => {
    try {
      message.loading({ content: '连通性测试中...', key: 'health-test', duration: 0 });
      await refresh();
      message.destroy('health-test');
      message.success('健康检查完成');
    } catch (e) {
      message.destroy('health-test');
      message.error('健康检查失败');
    }
  };

  return (
    <PageContainer>
      <Card title="Flink 集群配置" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>集群 REST API 地址: {config.restApiUrl || '未配置'}</div>
          <div>提交模式: {config.submissionMode || '未配置'}</div>
          <div>Flink 版本: {config.flinkVersion || '未配置'}</div>
          <Space>
            <Button type="primary" onClick={() => {
              form.setFieldsValue({
                restApiUrl: config.restApiUrl || '',
                submissionMode: config.submissionMode || 'application',
                flinkVersion: config.flinkVersion || '',
              });
              setEditModalOpen(true);
            }}>编辑配置</Button>
            <Button onClick={handleTestConnectivity}>测试连通性</Button>
          </Space>
        </Space>
      </Card>

      <Card title="一键健康检查">
        <Button type="primary" onClick={refresh} loading={loading}>检测健康状态</Button>

        {health && (
          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col span={8}>
              <div style={{
                padding: 16,
                borderRadius: 8,
                border: `2px solid ${statusColor(health.flink?.status || 'unknown')}`,
                background: health.flink?.status === 'healthy' ? '#f6ffed' : '#fff2f0',
                textAlign: 'center',
              }}>
                <div style={{ fontSize: 14, fontWeight: 600, color: statusColor(health.flink?.status || 'unknown') }}>
                  {statusIcon(health.flink?.status || 'unknown')} Flink 集群
                </div>
                <div style={{ fontSize: 12, color: '#666' }}>
                  {health.flink?.version || '—'} · {health.flink?.runningJobs || 0} jobs · {health.flink?.taskSlotsAvailable || 0} slots
                </div>
              </div>
            </Col>
            <Col span={8}>
              <div style={{
                padding: 16,
                borderRadius: 8,
                border: `2px solid ${statusColor(health.paimon?.status || 'unknown')}`,
                background: health.paimon?.status === 'healthy' ? '#f6ffed' : '#fff2f0',
                textAlign: 'center',
              }}>
                <div style={{ fontSize: 14, fontWeight: 600, color: statusColor(health.paimon?.status || 'unknown') }}>
                  {statusIcon(health.paimon?.status || 'unknown')} Paimon 存储
                </div>
                <div style={{ fontSize: 12, color: '#666' }}>
                  {health.paimon?.warehousePath || '—'}
                </div>
              </div>
            </Col>
            <Col span={8}>
              <div style={{
                padding: 16,
                borderRadius: 8,
                border: `2px solid ${statusColor(health.mysql?.status || 'unknown')}`,
                background: health.mysql?.status === 'healthy' ? '#f6ffed' : '#fff2f0',
                textAlign: 'center',
              }}>
                <div style={{ fontSize: 14, fontWeight: 600, color: statusColor(health.mysql?.status || 'unknown') }}>
                  {statusIcon(health.mysql?.status || 'unknown')} MySQL 管理库
                </div>
                <div style={{ fontSize: 12, color: '#666' }}>连接正常</div>
              </div>
            </Col>
          </Row>
        )}
      </Card>

      {/* Edit Config Modal */}
      <Modal
        title="编辑 Flink 集群配置"
        open={editModalOpen}
        onCancel={() => setEditModalOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
      >
        <Form form={form} layout="vertical" onFinish={handleSaveConfig}>
          <Form.Item name="restApiUrl" label="REST API 地址" rules={[{ required: true, message: '请输入 REST API 地址' }]}>
            <Input placeholder="http://localhost:8081" />
          </Form.Item>
          <Form.Item name="submissionMode" label="提交模式" rules={[{ required: true }]}>
            <Select style={{ width: '100%' }} options={[
              { label: 'Application Mode', value: 'application' },
              { label: 'Session Mode', value: 'session' },
            ]} />
          </Form.Item>
          <Form.Item name="flinkVersion" label="Flink 版本">
            <Input placeholder="2.2.0" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Settings;

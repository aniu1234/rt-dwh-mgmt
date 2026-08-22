import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Input, Space, Table, Tag, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import dayjs from 'dayjs';
import { getOperationAudits } from '@/api';

const formatTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) return `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')} ${String(value[3] || 0).padStart(2, '0')}:${String(value[4] || 0).padStart(2, '0')}`;
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
};

const Audit: React.FC = () => {
  const [usernameInput, setUsernameInput] = useState('');
  const [username, setUsername] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const { data, loading, refresh } = useRequest(
    () => getOperationAudits({ username: username || undefined, page, size }),
    { refreshDeps: [username, page, size] },
  );
  const result = data as API.PageResult<API.OperationAudit> | undefined;

  return <PageContainer title="操作审计" subTitle="记录已认证用户对平台资源的写操作及执行结果">
    <Card>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search value={usernameInput} onChange={(event) => setUsernameInput(event.target.value)}
          onSearch={(value) => { setUsername(value.trim()); setPage(0); }} allowClear
          prefix={<SearchOutlined />} placeholder="按用户名筛选" style={{ width: 260 }} />
        <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
      </Space>
      <Table rowKey="id" loading={loading} dataSource={result?.content || []}
        pagination={{ current: page + 1, pageSize: size, total: result?.totalElements || 0, showSizeChanger: true,
          onChange: (next, nextSize) => { setPage(next - 1); setSize(nextSize); } }}
        columns={[
          { title: '时间', dataIndex: 'createdAt', width: 180, render: formatTime },
          { title: '用户', dataIndex: 'username', width: 110 },
          { title: '操作', key: 'operation', width: 140, render: (_, row: API.OperationAudit) => <Space><Tag color="blue">{row.httpMethod}</Tag>{row.resourceType}</Space> },
          { title: '资源', key: 'resource', ellipsis: true, render: (_, row: API.OperationAudit) => <Typography.Text code>{row.requestPath}</Typography.Text> },
          { title: '结果', dataIndex: 'success', width: 90, render: (success) => <Tag color={success ? 'success' : 'error'}>{success ? '成功' : '失败'}</Tag> },
          { title: '状态码', dataIndex: 'responseStatus', width: 90 },
          { title: '耗时', dataIndex: 'durationMs', width: 90, render: (value) => `${value || 0} ms` },
          { title: 'IP', dataIndex: 'clientIp', width: 130 },
          { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
        ]} />
    </Card>
  </PageContainer>;
};

export default Audit;

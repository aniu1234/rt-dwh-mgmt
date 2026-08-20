import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Button, Space, Select, Input, message, Modal } from 'antd';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getDwhTables, syncMetadataFromPaimon, triggerCompact, triggerExpireSnapshots } from '@/api';

const layerColorMap: Record<string, string> = {
  ods: 'blue',
  dwd: 'green',
  dws: 'orange',
  ads: 'red',
};

const formatSize = (bytes?: number) => {
  if (!bytes) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let size = bytes;
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++; }
  return `${size.toFixed(1)} ${units[i]}`;
};

const DwhTableList: React.FC = () => {
  const [layerFilter, setLayerFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState<string | undefined>();

  const { data, loading, refresh } = useRequest(() =>
    getDwhTables({ layer: layerFilter, keyword }),
  );

  const tables = (data || []) as API.DwhTableMeta[];

  const handleSyncMetadata = async () => {
    try {
      const count = await syncMetadataFromPaimon();
      message.success(`元数据同步完成，共 ${count} 表`);
      refresh();
    } catch (e) {
      message.error('同步失败');
    }
  };

  return (
    <PageContainer>
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary" icon={<ReloadOutlined />} onClick={handleSyncMetadata}>
            从 Paimon 同步元数据
          </Button>
          <Input
            placeholder="搜索表名"
            prefix={<SearchOutlined />}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
          />
          <Select
            placeholder="全部分层"
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
        </Space>

        <Table<API.DwhTableMeta>
          dataSource={tables}
          rowKey="id"
          loading={loading}
          columns={[
            { title: '库名', dataIndex: 'database', key: 'db', width: 80 },
            { title: '表名', dataIndex: 'tableName', key: 'table' },
            {
              title: '分层',
              dataIndex: 'layer',
              key: 'layer',
              render: (v) => <Tag color={layerColorMap[v]}>{v.toUpperCase()}</Tag>,
              width: 80,
            },
            { title: '业务描述', dataIndex: 'businessDesc', key: 'desc', ellipsis: true },
            { title: '文件数', dataIndex: 'fileCount', key: 'files', width: 80, render: (v) => v ?? '—' },
            { title: '数据大小', dataIndex: 'totalSize', key: 'size', width: 100, render: (v) => formatSize(v) },
            {
              title: '操作',
              key: 'action',
              width: 120,
              render: (_, record) => (
                <Space>
                  <Button size="small" type="link" href={`/dwh/tables/${record.id}`}>详情</Button>
                  <Button size="small" type="link" href={`/dwh/maintenance?tableId=${record.id}`}>维护</Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </PageContainer>
  );
};

export default DwhTableList;

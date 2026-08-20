import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Table, Tag, Tabs, Button, Space, Modal, Input, message } from 'antd';
import { useParams } from '@umijs/max';
import { useRequest } from '@umijs/max';
import {
  cleanOrphanFiles,
  getDwhTableColumns,
  getDwhTableDetail,
  getMaintenanceLogs,
  triggerCompact,
  triggerExpireSnapshots,
  updateDwhColumnComment,
  updateTableBusinessDesc,
} from '@/api';

const DwhTableDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const tableId = parseInt(id || '0');
  const [editingDesc, setEditingDesc] = useState(false);
  const [descValue, setDescValue] = useState('');
  const [editingColumn, setEditingColumn] = useState<API.DwhColumnMeta>();
  const [columnComment, setColumnComment] = useState('');

  const { data: tableData, refresh: refreshTable } = useRequest(() => getDwhTableDetail(tableId));
  const { data: columnsData, refresh: refreshColumns } = useRequest(() => getDwhTableColumns(tableId));
  const { data: logsData, refresh: refreshLogs } = useRequest(() => getMaintenanceLogs());

  const table = tableData as API.DwhTableMeta | undefined;
  const columns = (columnsData || []) as API.DwhColumnMeta[];
  const maintenanceLogs = (logsData || []) as API.MaintenanceLog[];

  if (!table) return <PageContainer>加载中...</PageContainer>;

  const handleCompact = async () => {
    try {
      await triggerCompact(tableId, 'minor');
      message.success('Compact 操作已触发');
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleExpireSnapshots = async () => {
    try {
      await triggerExpireSnapshots(tableId, 10);
      message.success('快照过期清理已触发');
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleUpdateDesc = async () => {
    try {
      await updateTableBusinessDesc(tableId, descValue);
      message.success('业务描述已更新');
      setEditingDesc(false);
      refreshTable();
    } catch (e) {
      message.error('更新失败');
    }
  };

  const handleUpdateColumnComment = async () => {
    if (!editingColumn) return;
    try {
      await updateDwhColumnComment(editingColumn.id, columnComment);
      message.success('字段注释已更新');
      setEditingColumn(undefined);
      refreshColumns();
    } catch (e) {
      message.error('更新字段注释失败');
    }
  };

  const handleOrphanCleanup = async () => {
    try {
      await cleanOrphanFiles(tableId);
      message.success('孤立文件清理已触发');
      refreshLogs();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const formatSize = (bytes?: number) => {
    if (!bytes) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    let size = bytes;
    while (size >= 1024 && i < units.length - 1) { size /= 1024; i++; }
    return `${size.toFixed(1)} ${units[i]}`;
  };

  return (
    <PageContainer>
      <Tabs
        items={[
          {
            key: 'structure',
            label: '表结构',
            children: (
              <>
                <Card title="基本信息" style={{ marginBottom: 16 }}>
                  <Descriptions column={2}>
                    <Descriptions.Item label="库名">{table.database || table.paimonDb}</Descriptions.Item>
                    <Descriptions.Item label="表名">{table.tableName || table.paimonTable}</Descriptions.Item>
                    <Descriptions.Item label="分层">
                      <Tag color={{
                        ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
                      }[table.layer]}>{table.layer.toUpperCase()}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="业务描述">
                      {editingDesc ? (
                        <Space>
                          <Input value={descValue} onChange={(e) => setDescValue(e.target.value)} style={{ width: 300 }} />
                          <Button size="small" type="primary" onClick={handleUpdateDesc}>保存</Button>
                          <Button size="small" onClick={() => setEditingDesc(false)}>取消</Button>
                        </Space>
                      ) : (
                        <Space>
                          <span>{table.businessDesc || '—'}</span>
                          <Button size="small" type="link" onClick={() => { setDescValue(table.businessDesc || ''); setEditingDesc(true); }}>编辑</Button>
                        </Space>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="分区键">{table.partitionKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="主键">{table.primaryKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="快照数">{table.snapshotCount ?? '—'}</Descriptions.Item>
                    <Descriptions.Item label="文件数 / 数据大小">{table.fileCount ?? '—'} · {formatSize(table.totalSizeBytes || table.totalSize)}</Descriptions.Item>
                  </Descriptions>
                </Card>

                <Card title="字段列表">
                  <Table<API.DwhColumnMeta>
                    dataSource={columns}
                    rowKey="id"
                    size="small"
                    columns={[
                      { title: '字段名', dataIndex: 'columnName', key: 'name' },
                      { title: '类型', dataIndex: 'columnType', key: 'type', width: 120 },
                      { title: '主键', dataIndex: 'isPartitionKey', key: 'pk', width: 60, render: (v) => v ? '✓' : '' },
                      { title: '可为空', dataIndex: 'nullable', key: 'nullable', width: 60, render: (v) => v ? '✓' : '' },
                      { title: '业务注释', dataIndex: 'businessComment', key: 'comment', ellipsis: true, render: (value) => value || '—' },
                      {
                        title: '操作',
                        key: 'action',
                        width: 80,
                        render: (_, record) => (
                          <Button size="small" type="link" onClick={() => {
                            setEditingColumn(record);
                            setColumnComment(record.businessComment || record.comment || '');
                          }}>编辑注释</Button>
                        ),
                      },
                    ]}
                  />
                </Card>
              </>
            ),
          },
          {
            key: 'maintenance',
            label: '表维护',
            children: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" onClick={handleCompact}>触发 Compact</Button>
                  <Button type="primary" danger onClick={handleExpireSnapshots}>过期快照清理</Button>
                  <Button onClick={handleOrphanCleanup}>清理孤立文件</Button>
                </Space>
                <Table<API.MaintenanceLog>
                  dataSource={maintenanceLogs}
                  rowKey="id"
                  columns={[
                    { title: '时间', dataIndex: 'createdAt', key: 'time', render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
                    { title: '操作', dataIndex: 'operation', key: 'op' },
                    { title: '触发方式', dataIndex: 'triggerType', key: 'trigger' },
                    { title: '状态', dataIndex: 'status', key: 'status' },
                    { title: '耗时(ms)', dataIndex: 'durationMs', key: 'duration', render: (v: number) => v ?? '—' },
                    { title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true },
                  ]}
                  locale={{ emptyText: '暂无维护操作日志' }}
                />
              </Card>
            ),
          },
          {
            key: 'snapshots',
            label: '快照历史',
            children: <Card><Table dataSource={[]} rowKey="id" locale={{ emptyText: '暂无快照数据' }} columns={[
              { title: '快照ID', dataIndex: 'snapshotId' },
              { title: '提交时间', dataIndex: 'commitTime' },
              { title: '文件数', dataIndex: 'fileCount' },
              { title: '数据大小', dataIndex: 'sizeBytes' },
            ]} /></Card>,
          },
        ]}
      />
      <Modal
        title={`编辑字段注释：${editingColumn?.columnName || ''}`}
        open={Boolean(editingColumn)}
        onCancel={() => setEditingColumn(undefined)}
        onOk={handleUpdateColumnComment}
        okText="保存"
      >
        <Input.TextArea
          rows={4}
          value={columnComment}
          onChange={(event) => setColumnComment(event.target.value)}
          placeholder="请输入字段的业务含义"
        />
      </Modal>
    </PageContainer>
  );
};

export default DwhTableDetail;

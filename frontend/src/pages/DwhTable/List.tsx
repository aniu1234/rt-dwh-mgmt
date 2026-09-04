import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Col, Empty, Input, Modal, Row, Select, Space, Statistic, Table, Tag, Typography, message } from 'antd';
import { ApartmentOutlined, BarChartOutlined, CloudSyncOutlined, DatabaseOutlined, HddOutlined, ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import { getDwhTables, syncMetadataFromPaimon } from '@/api';
import './index.less';

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const layerMetaMap: Record<string, {
  label: string;
  color: string;
  purpose: string;
  tableMode: string;
  partitionPolicy: string;
  consumer: string;
}> = {
  ods: {
    label: '原始层', color: '#1677ff', purpose: '源数据对齐，保留可追溯明细',
    tableMode: '主键 Upsert；优先接收完整 CDC 变更', partitionPolicy: '按创建日或业务日；避免频繁修改历史分区',
    consumer: '清洗任务、审计与重放',
  },
  dwd: {
    label: '明细层', color: '#52c41a', purpose: '清洗、标准化后的原子业务事实',
    tableMode: '主键 Upsert；按读写与 Doris 兼容性选择表模式', partitionPolicy: '按业务日期和稳定维度分区',
    consumer: 'DWS 汇总、明细分析',
  },
  dws: {
    label: '汇总层', color: '#fa8c16', purpose: '面向主题的公共汇总与宽表',
    tableMode: '增量聚合或周期覆盖，口径可复用', partitionPolicy: '通常按业务日期分区',
    consumer: '指标、报表和 ADS',
  },
  ads: {
    label: '应用层', color: '#f5222d', purpose: '面向具体报表、接口和分析场景',
    tableMode: '查询导向，可由上游稳定重建', partitionPolicy: '按交付周期或服务主题组织',
    consumer: '数据 API、看板和导出',
  },
};

const sensitivityMeta: Record<string, { label: string; color: string }> = {
  public: { label: '公开', color: 'green' },
  internal: { label: '内部', color: 'blue' },
  confidential: { label: '机密', color: 'orange' },
  restricted: { label: '受限', color: 'red' },
};

const lifecycleMeta: Record<string, { label: string; color: string }> = {
  active: { label: '使用中', color: 'success' },
  deprecated: { label: '待下线', color: 'warning' },
  offline: { label: '已下线', color: 'default' },
};

const formatSize = (bytes?: number) => {
  if (bytes === undefined || bytes === null) return '—';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let index = 0;
  let size = bytes;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
};

const formatDateTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value;
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`;
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
};

const DwhTableList: React.FC = () => {
  const access = useAccess();
  const [layer, setLayer] = useState<string>();
  const [database, setDatabase] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [syncing, setSyncing] = useState(false);
  const [storageGuideOpen, setStorageGuideOpen] = useState(false);
  const { data, loading, refresh } = useRequest(getDwhTables);
  const tables = (data || []) as API.DwhTableMeta[];

  const databases = useMemo(() => Array.from(new Set(tables.map((table) => table.paimonDb)))
    .sort().map((value) => ({ label: value, value })), [tables]);
  const filteredTables = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return tables.filter((table) => (!layer || table.layer === layer)
      && (!database || table.paimonDb === database)
      && (!normalizedKeyword
        || `${table.paimonDb}.${table.paimonTable} ${table.businessDesc || ''}`
          .toLowerCase().includes(normalizedKeyword)));
  }, [tables, layer, database, keyword]);

  const totalSize = tables.reduce((sum, table) => sum + (table.totalSizeBytes || 0), 0);
  const totalRecords = tables.reduce((sum, table) => sum + (table.recordCount || 0), 0);
  const layerStats = Object.entries(layerMetaMap).map(([value, meta]) => ({
    value, ...meta, count: tables.filter((table) => table.layer === value).length,
  }));
  const hasFilters = Boolean(keyword.trim() || database || layer);

  const handleSyncMetadata = async () => {
    setSyncing(true);
    try {
      const count = await syncMetadataFromPaimon();
      message.success(`元数据同步完成，共发现 ${count} 张 Paimon 表`);
      refresh();
    } catch (error: any) {
      message.error(error?.message || '元数据同步失败');
    } finally {
      setSyncing(false);
    }
  };

  return (
    <PageContainer
      className="paimon-assets-page"
      title="Paimon 湖仓资产"
      subTitle="统一管理 Catalog 表、数仓分层、治理属性与存储快照"
      extra={<Space size={8}>
        <Tag className="paimon-catalog-tag" icon={<DatabaseOutlined />}>JDBC Catalog</Tag>
        <Button icon={<ApartmentOutlined />} onClick={() => setStorageGuideOpen(true)}>分层存储方案</Button>
        <Button icon={<ReloadOutlined />} onClick={() => refresh()}>刷新</Button>
      </Space>}
    >
      <Row className="paimon-metrics" gutter={[12, 12]}>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-blue"><TableOutlined /></span><Statistic title="Catalog 表" value={tables.length} suffix="张" /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-cyan"><DatabaseOutlined /></span><Statistic title="数据库" value={databases.length} suffix="个" /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-green"><BarChartOutlined /></span><Statistic title="总记录数" value={totalRecords} formatter={(value) => Number(value).toLocaleString()} /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-purple"><HddOutlined /></span><Statistic title="数据文件容量" value={formatSize(totalSize)} /></Card></Col>
      </Row>

      <Alert
        className="paimon-storage-note"
        type="info"
        showIcon
        message="一个物理 Warehouse，四个逻辑数据层"
        description="Catalog 保存元数据指针；ODS、DWD、DWS、ADS 通过 Database／表目录分层。快照保留用于回溯与恢复，不等于业务数据保留期。"
        action={<Button size="small" type="link" onClick={() => setStorageGuideOpen(true)}>查看方案</Button>}
      />

      <Card className="paimon-layer-card">
        <div className="paimon-layer-heading"><div><strong>数仓分层</strong><Typography.Text type="secondary">按治理层级快速筛选 Paimon 表</Typography.Text></div><Typography.Text type="secondary">共 {tables.length} 张表</Typography.Text></div>
        <div className="paimon-layer-list">
          {layerStats.map((item) => <button key={item.value} type="button" className={`paimon-layer-item${layer === item.value ? ' is-active' : ''}`}
            aria-pressed={layer === item.value}
            onClick={() => setLayer(layer === item.value ? undefined : item.value)}>
            <span className="paimon-layer-dot" style={{ background: item.color }} />
            <span className="paimon-layer-code">{item.value.toUpperCase()}</span>
            <span className="paimon-layer-label">{item.label}</span>
            <strong>{item.count}</strong>
          </button>)}
        </div>
      </Card>

      <Card className="paimon-assets-card" title="Catalog 表资产" extra={<Tag className="paimon-result-tag">{filteredTables.length} / {tables.length} 张</Tag>}>
        <div className="paimon-assets-toolbar">
          <div className="paimon-filter-group">
            <Input className="paimon-search" allowClear placeholder="搜索库名、表名或业务描述" prefix={<SearchOutlined />}
              value={keyword} onChange={(event) => setKeyword(event.target.value)} />
            <Select className="paimon-database-filter" placeholder="全部数据库" allowClear value={database} onChange={setDatabase}
              options={databases} />
            <Select className="paimon-layer-filter" placeholder="全部分层" allowClear value={layer} onChange={setLayer}
              options={[
                { label: 'ODS 原始层', value: 'ods' },
                { label: 'DWD 明细层', value: 'dwd' },
                { label: 'DWS 汇总层', value: 'dws' },
                { label: 'ADS 应用层', value: 'ads' },
              ]} />
          </div>
          <div className="paimon-toolbar-actions">
            {hasFilters && <Button type="link" onClick={() => { setKeyword(''); setDatabase(undefined); setLayer(undefined); }}>重置筛选</Button>}
            {access.canManageDwh && <Button type="primary" icon={<CloudSyncOutlined />} loading={syncing} onClick={handleSyncMetadata}>
              同步 Catalog 元数据
            </Button>}
          </div>
        </div>

        <Table<API.DwhTableMeta>
          className="paimon-assets-table"
          dataSource={filteredTables}
          rowKey="id"
          loading={loading}
          size="small"
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 张表` }}
          locale={{ emptyText: <Empty className="paimon-empty" image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={hasFilters ? '没有匹配的 Paimon 表' : '尚未同步 Paimon Catalog 元数据'}>
            {access.canManageDwh && !hasFilters && <Button type="link" icon={<CloudSyncOutlined />} loading={syncing} onClick={handleSyncMetadata}>立即同步元数据</Button>}
          </Empty> }}
          columns={[
            { title: 'Paimon 表', key: 'asset', width: 240, render: (_, record) => <div className="paimon-table-identity">
              <Typography.Text strong ellipsis>{record.paimonTable}</Typography.Text>
              <Typography.Text className="paimon-table-path" code>{record.paimonDb}.{record.paimonTable}</Typography.Text>
            </div> },
            { title: '分层', dataIndex: 'layer', width: 78,
              render: (value) => <Tag color={layerColorMap[value]}>{String(value).toUpperCase()}</Tag> },
            { title: '业务描述', dataIndex: 'businessDesc', width: 210, ellipsis: true, render: (value) => value || <Typography.Text type="secondary">暂未补充</Typography.Text> },
            { title: '治理信息', key: 'governance', width: 190, render: (_, record) => {
              const sensitivity = sensitivityMeta[record.sensitivityLevel || 'internal'] || sensitivityMeta.internal;
              const lifecycle = lifecycleMeta[record.lifecycleStatus || 'active'] || lifecycleMeta.active;
              return <div className="paimon-governance-cell"><div><span>{record.owner || '未指定'}</span><span className="paimon-domain">{record.businessDomain || '未归属'}</span></div><div><Tag color={sensitivity.color}>{sensitivity.label}</Tag><Tag color={lifecycle.color}>{lifecycle.label}</Tag></div></div>;
            } },
            { title: '记录数', dataIndex: 'recordCount', width: 105, align: 'right',
              render: (value) => value === undefined || value === null ? '—' : Number(value).toLocaleString() },
            { title: '存储概况', key: 'storage', width: 150, render: (_, record) => <div className="paimon-storage-cell"><span>{formatSize(record.totalSizeBytes)} · {record.fileCount ?? '—'} 文件</span><span>{record.snapshotCount ?? '—'} 个快照</span></div> },
            { title: '最近提交', dataIndex: 'latestCommitTime', width: 170, render: formatDateTime },
            { title: '操作', fixed: 'right', width: 120, render: (_, record) => (
              <Space size={4}>
                <Button size="small" type="link" href={`/dwh/tables/${record.id}`}>查看</Button>
                {access.canManageDwh && <Button size="small" type="link" href={`/dwh/maintenance?tableId=${record.id}`}>维护</Button>}
              </Space>
            ) },
          ]}
          scroll={{ x: 1260 }}
        />
      </Card>

      <Modal
        className="paimon-storage-guide"
        title="Paimon 分层存储方案"
        open={storageGuideOpen}
        width={920}
        footer={<Button type="primary" onClick={() => setStorageGuideOpen(false)}>知道了</Button>}
        onCancel={() => setStorageGuideOpen(false)}
      >
        <div className="paimon-storage-path" aria-label="Paimon 存储层级">
          {['JDBC Catalog\n元数据与指针', '共享 Warehouse\n对象／文件存储', 'Database\nods · dwd · dws · ads', 'Table\n分区与 Bucket', 'Snapshot → Manifest → Data File'].map((item, index) => (
            <React.Fragment key={item}>
              {index > 0 && <span className="paimon-storage-arrow">→</span>}
              <span className="paimon-storage-node">{item.split('\n').map((line) => <span key={line}>{line}</span>)}</span>
            </React.Fragment>
          ))}
        </div>
        <Table
          className="paimon-storage-guide-table"
          rowKey="code"
          size="small"
          pagination={false}
          dataSource={Object.entries(layerMetaMap).map(([code, meta]) => ({ code, ...meta }))}
          columns={[
            { title: '分层', dataIndex: 'code', width: 92, render: (value: string, record: any) => <Tag color={record.color}>{value.toUpperCase()} · {record.label}</Tag> },
            { title: '定位', dataIndex: 'purpose', width: 190 },
            { title: '写入／表模型', dataIndex: 'tableMode', width: 245 },
            { title: '分区建议', dataIndex: 'partitionPolicy', width: 220 },
            { title: '主要消费方', dataIndex: 'consumer' },
          ]}
        />
        <Alert
          className="paimon-storage-guide-warning"
          type="warning"
          showIcon
          message="生命周期需要拆成两套策略"
          description="Snapshot Expire 只清理不再被保留快照引用的历史文件；业务数据到期删除需要另行配置分区生命周期。长期审计节点应先创建 Tag，再清理快照。"
        />
      </Modal>
    </PageContainer>
  );
};

export default DwhTableList;

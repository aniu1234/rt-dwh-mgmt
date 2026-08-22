import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button, Card, DatePicker, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Switch, Table, Tabs, Tag,
} from 'antd';
import { ApartmentOutlined, BranchesOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import dayjs, { Dayjs } from 'dayjs';
import {
  addTaskDependency, createTaskBackfill, getTaskVersions, getWorkflowGraph, getWorkflowInstances,
  publishTaskVersion, removeTaskDependency, rollbackTaskVersion, retryWorkflowInstance,
  cancelWorkflowInstance,
  configureTaskOutputs, configureTaskSchedule, deleteTaskSchedule, getDatasetProductions,
  getTaskOutputs, getTaskSchedules,
} from '@/api';

const statusColors: Record<string, string> = {
  waiting: 'default', queued: 'processing', running: 'blue', success: 'success', failed: 'error', cancelled: 'default',
};

const formatTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) return `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')} ${String(value[3] || 0).padStart(2, '0')}:${String(value[4] || 0).padStart(2, '0')}`;
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
};

const Workflow: React.FC = () => {
  const access = useAccess();
  const [dependencyOpen, setDependencyOpen] = useState(false);
  const [publishTask, setPublishTask] = useState<API.SyncTask>();
  const [backfillTask, setBackfillTask] = useState<API.SyncTask>();
  const [versionTask, setVersionTask] = useState<API.SyncTask>();
  const [versions, setVersions] = useState<API.TaskDefinitionVersion[]>([]);
  const [scheduleTask, setScheduleTask] = useState<API.SyncTask>();
  const [outputTask, setOutputTask] = useState<API.SyncTask>();
  const [outputs, setOutputs] = useState<API.TaskOutputDataset[]>([]);
  const [productionOutput, setProductionOutput] = useState<API.TaskOutputDataset>();
  const [productions, setProductions] = useState<API.DatasetProduction[]>([]);
  const [dependencyForm] = Form.useForm();
  const [publishForm] = Form.useForm();
  const [backfillForm] = Form.useForm();
  const [scheduleForm] = Form.useForm();
  const [outputForm] = Form.useForm();

  const graphRequest = useRequest(getWorkflowGraph);
  const instancesRequest = useRequest(() => getWorkflowInstances({ limit: 200 }), { pollingInterval: 5000 });
  const schedulesRequest = useRequest(getTaskSchedules, { pollingInterval: 10000 });
  const graph = graphRequest.data as API.WorkflowGraph | undefined;
  const tasks = graph?.tasks || [];
  const dependencies = graph?.dependencies || [];
  const taskMap = useMemo(() => new Map(tasks.map((task) => [task.id, task])), [tasks]);
  const scheduleMap = useMemo(() => new Map(((schedulesRequest.data || []) as API.TaskSchedule[]).map((item) => [item.taskId, item])), [schedulesRequest.data]);

  const refreshAll = () => { graphRequest.refresh(); instancesRequest.refresh(); };

  const openVersions = async (task: API.SyncTask) => {
    setVersionTask(task);
    setVersions(await getTaskVersions(task.id));
  };
  const openSchedule = (task: API.SyncTask) => {
    const schedule = scheduleMap.get(task.id); setScheduleTask(task); scheduleForm.resetFields();
    scheduleForm.setFieldsValue(schedule ? { ...schedule } : { cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', businessDateOffset: -1, parametersJson: '{}', enabled: true });
  };
  const openOutputs = async (task: API.SyncTask) => {
    setOutputTask(task); const values = await getTaskOutputs(task.id); setOutputs(values);
    outputForm.setFieldsValue({ outputs: values.map(({ id, taskId, ...item }) => item) });
  };

  const taskColumns = [
    { title: '任务', key: 'task', render: (_: unknown, task: API.SyncTask) => <Space direction="vertical" size={0}>
      <b>{task.taskName || task.name}</b><span style={{ color: '#8c8c8c' }}>ID {task.id} · {task.taskType}</span>
    </Space> },
    { title: '上游依赖', key: 'upstream', render: (_: unknown, task: API.SyncTask) => {
      const upstream = dependencies.filter((item) => item.downstreamTaskId === task.id);
      return upstream.length ? <Space wrap>{upstream.map((item) => <Tag key={item.id} closable={access.canManageTask}
        onClose={(event) => { event.preventDefault(); removeTaskDependency(item.upstreamTaskId, item.downstreamTaskId)
          .then(() => { message.success('依赖已删除'); graphRequest.refresh(); }); }}>
        {taskMap.get(item.upstreamTaskId)?.taskName || `任务 ${item.upstreamTaskId}`}
      </Tag>)}</Space> : <span style={{ color: '#bfbfbf' }}>无上游，可直接调度</span>;
    } },
    { title: '周期调度', key: 'schedule', width: 190, render: (_: unknown, task: API.SyncTask) => { const item=scheduleMap.get(task.id); return item ? <Space direction="vertical" size={0}><Tag color={item.enabled?'processing':'default'}>{item.enabled?'已启用':'已停用'} · {item.cronExpression}</Tag><span style={{color:'#8c8c8c'}}>下次 {formatTime(item.nextRunAt)}</span></Space> : <Tag>未配置</Tag>; } },
    { title: '发布状态', key: 'status', width: 120, render: (_: unknown, task: API.SyncTask) => <Tag color={task.status === 'draft' ? 'default' : 'blue'}>{task.status}</Tag> },
    { title: '操作', key: 'action', width: 420, render: (_: unknown, task: API.SyncTask) => <Space wrap>
      <Button size="small" icon={<HistoryOutlined />} onClick={() => openVersions(task)}>版本</Button>
      {access.canManageTask && <><Button size="small" onClick={() => { setPublishTask(task); publishForm.setFieldsValue({ changeSummary: '发布任务配置' }); }}>发布版本</Button>
        {task.taskType !== 'cdc_sync' && <Button size="small" onClick={() => { setBackfillTask(task); backfillForm.setFieldsValue({ dates: [dayjs(), dayjs()] }); }}>补数</Button>}
        {task.taskType !== 'cdc_sync' && <Button size="small" onClick={() => openSchedule(task)}>调度</Button>}
        {task.taskType !== 'cdc_sync' && <Button size="small" onClick={() => openOutputs(task)}>产出资源</Button>}</>}
    </Space> },
  ];

  const instanceColumns = [
    { title: '实例 ID', dataIndex: 'id', width: 90 },
    { title: '任务', dataIndex: 'taskId', render: (id: number) => taskMap.get(id)?.taskName || `任务 ${id}` },
    { title: '业务日期', dataIndex: 'businessDate', width: 130 },
    { title: '触发方式', dataIndex: 'triggerType', width: 110 },
    { title: '状态', dataIndex: 'status', width: 110, render: (status: string) => <Tag color={statusColors[status]}>{status}</Tag> },
    { title: '执行信息', key: 'executor', width: 220, render: (_: unknown, record: API.TaskRunInstance) => <Space direction="vertical" size={0}>
      <span>{record.executorId || '等待领取'}</span>
      <span style={{ color: '#8c8c8c' }}>{record.externalJobId ? `Job ${record.externalJobId.slice(0, 12)}…` : `已重试 ${record.retryCount || 0} 次`}</span>
    </Space> },
    { title: '下次执行 / 租约', key: 'schedule', width: 180, render: (_: unknown, record: API.TaskRunInstance) => formatTime(record.nextRetryAt || record.leaseExpiresAt) },
    { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', width: 180, render: formatTime },
    { title: '操作', key: 'actions', fixed: 'right' as const, width: 130, render: (_: unknown, record: API.TaskRunInstance) => <Space>
      {access.canManageTask && record.status === 'failed' && <Button size="small" type="primary" onClick={async () => {
        await retryWorkflowInstance(record.id); message.success('实例已重新入队'); instancesRequest.refresh();
      }}>重试</Button>}
      {access.canManageTask && ['waiting', 'queued', 'running'].includes(record.status) && <Popconfirm title="确认取消该实例？运行中的 Flink Job 也会被取消。" onConfirm={async () => {
        await cancelWorkflowInstance(record.id); message.success('实例已取消'); instancesRequest.refresh();
      }}><Button size="small" danger>取消</Button></Popconfirm>}
    </Space> },
  ];

  return <PageContainer title="任务编排" subTitle="任务依赖、版本发布、运行实例与补数控制面">
    <Card>
      <Space style={{ marginBottom: 16 }}>
        {access.canManageTask && <Button type="primary" icon={<PlusOutlined />} onClick={() => setDependencyOpen(true)}>添加依赖</Button>}
        <Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新</Button>
        <Tag icon={<ApartmentOutlined />} color="blue">{tasks.length} 个任务</Tag>
        <Tag icon={<BranchesOutlined />}>{dependencies.length} 条依赖</Tag>
      </Space>
      <Tabs items={[
        { key: 'dag', label: 'DAG 与版本', children: <Table rowKey="id" loading={graphRequest.loading} dataSource={tasks} columns={taskColumns} pagination={false} /> },
        { key: 'runs', label: '运行实例', children: <Table rowKey="id" loading={instancesRequest.loading} dataSource={(instancesRequest.data || []) as API.TaskRunInstance[]} columns={instanceColumns} pagination={{ pageSize: 20 }} scroll={{ x: 1450 }} /> },
      ]} />
    </Card>

    <Modal title="添加任务依赖" open={dependencyOpen} onCancel={() => setDependencyOpen(false)} onOk={() => dependencyForm.submit()} destroyOnClose>
      <Form form={dependencyForm} layout="vertical" onFinish={async (values) => {
        await addTaskDependency(values); message.success('依赖创建成功'); setDependencyOpen(false); dependencyForm.resetFields(); graphRequest.refresh();
      }}>
        <Form.Item name="upstreamTaskId" label="上游任务" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tasks.map((task) => ({ value: task.id, label: task.taskName || task.name }))} /></Form.Item>
        <Form.Item name="downstreamTaskId" label="下游任务" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tasks.map((task) => ({ value: task.id, label: task.taskName || task.name }))} /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`发布版本：${publishTask?.taskName || ''}`} open={!!publishTask} onCancel={() => setPublishTask(undefined)} onOk={() => publishForm.submit()} destroyOnClose>
      <Form form={publishForm} layout="vertical" onFinish={async ({ changeSummary }) => {
        if (!publishTask) return; await publishTaskVersion(publishTask.id, changeSummary); message.success('版本发布成功'); setPublishTask(undefined);
      }}><Form.Item name="changeSummary" label="变更说明" rules={[{ required: true }]}><Input.TextArea rows={3} maxLength={256} showCount /></Form.Item></Form>
    </Modal>

    <Modal title={`创建补数：${backfillTask?.taskName || ''}`} open={!!backfillTask} onCancel={() => setBackfillTask(undefined)} onOk={() => backfillForm.submit()} destroyOnClose>
      <Form form={backfillForm} layout="vertical" onFinish={async ({ dates, parametersJson }: { dates: [Dayjs, Dayjs]; parametersJson?: string }) => {
        if (!backfillTask) return; await createTaskBackfill(backfillTask.id, { startDate: dates[0].format('YYYY-MM-DD'), endDate: dates[1].format('YYYY-MM-DD'), parametersJson });
        message.success('补数实例已创建'); setBackfillTask(undefined); instancesRequest.refresh();
      }}>
        <Form.Item name="dates" label="业务日期范围" rules={[{ required: true }]}><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="parametersJson" label="运行参数（JSON）"><Input.TextArea rows={4} placeholder='例如：{"partition":"dt=${bizdate}"}' /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`版本历史：${versionTask?.taskName || ''}`} width={760} open={!!versionTask} footer={null} onCancel={() => setVersionTask(undefined)}>
      <Table rowKey="id" dataSource={versions} pagination={false} columns={[
        { title: '版本', dataIndex: 'versionNo', render: (value) => <Tag color="blue">V{value}</Tag> },
        { title: '变更说明', dataIndex: 'changeSummary' },
        { title: '发布时间', dataIndex: 'createdAt', render: formatTime },
        { title: '操作', render: (_: unknown, version: API.TaskDefinitionVersion) => <Popconfirm title="确认回滚到此版本？仅 draft 任务可回滚" onConfirm={async () => {
          if (!versionTask) return; await rollbackTaskVersion(versionTask.id, version.versionNo); message.success('配置已回滚'); graphRequest.refresh();
        }}><Button size="small">回滚</Button></Popconfirm> },
      ]} />
    </Modal>

    <Modal title={`周期调度：${scheduleTask?.taskName || ''}`} open={!!scheduleTask} onCancel={() => setScheduleTask(undefined)} onOk={() => scheduleForm.submit()} destroyOnClose>
      <Form form={scheduleForm} layout="vertical" onFinish={async (values) => { if(!scheduleTask)return; await configureTaskSchedule(scheduleTask.id, values); message.success('周期调度已保存'); setScheduleTask(undefined); schedulesRequest.refresh(); }}>
        <Form.Item name="enabled" label="启用调度" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="cronExpression" label="Cron 表达式" extra="六段 Cron：秒 分 时 日 月 周" rules={[{required:true}]}><Input placeholder="0 0 2 * * *" /></Form.Item>
        <Space style={{width:'100%'}} align="start">
          <Form.Item name="timezone" label="时区" rules={[{required:true}]}><Select style={{width:200}} options={[{value:'Asia/Shanghai'},{value:'UTC'},{value:'Asia/Hong_Kong'}]} /></Form.Item>
          <Form.Item name="businessDateOffset" label="业务日期偏移"><InputNumber min={-366} max={366} addonAfter="天" /></Form.Item>
        </Space>
        <Form.Item name="parametersJson" label="运行参数（JSON）"><Input.TextArea rows={4} /></Form.Item>
        {scheduleTask && scheduleMap.has(scheduleTask.id) && <Popconfirm title="确认删除周期调度？" onConfirm={async()=>{await deleteTaskSchedule(scheduleTask.id);message.success('调度已删除');setScheduleTask(undefined);schedulesRequest.refresh();}}><Button danger>删除调度</Button></Popconfirm>}
      </Form>
    </Modal>

    <Modal title={`产出数据资源：${outputTask?.taskName || ''}`} width={1000} open={!!outputTask} onCancel={() => setOutputTask(undefined)} onOk={() => outputForm.submit()} destroyOnClose>
      <Form form={outputForm} layout="vertical" onFinish={async ({outputs: values=[]})=>{if(!outputTask)return;await configureTaskOutputs(outputTask.id,values);message.success('产出资源已保存');setOutputTask(undefined);}}>
        <Form.List name="outputs">{(fields,{add,remove})=><Space direction="vertical" style={{width:'100%'}}>
          {fields.map((field,index)=><Card key={field.key} size="small" title={`数据资源 ${index+1}`} extra={<Button danger type="link" onClick={()=>remove(field.name)}>移除</Button>}>
            <Space wrap align="start"><Form.Item name={[field.name,'catalogName']} label="Catalog" rules={[{required:true}]}><Input style={{width:160}} /></Form.Item><Form.Item name={[field.name,'databaseName']} label="Database" rules={[{required:true}]}><Input style={{width:140}} /></Form.Item><Form.Item name={[field.name,'tableName']} label="Table" rules={[{required:true}]}><Input style={{width:220}} /></Form.Item><Form.Item name={[field.name,'layer']} label="分层" rules={[{required:true}]}><Select style={{width:100}} options={['ods','dwd','dws','ads'].map(value=>({value}))} /></Form.Item><Form.Item name={[field.name,'slaMinutes']} label="SLA"><InputNumber min={1} addonAfter="分钟" /></Form.Item></Space>
            <Space wrap align="start"><Form.Item name={[field.name,'owner']} label="负责人"><Input /></Form.Item><Form.Item name={[field.name,'businessDesc']} label="业务说明"><Input style={{width:360}} /></Form.Item><Form.Item name={[field.name,'qualityGateEnabled']} label="质量门禁" extra="命中该表的启用规则失败时阻止资源变为可用" valuePropName="checked"><Switch /></Form.Item></Space>
            {outputs[index]?.lastProducedAt && <Space><Tag color="success">最近产出 {formatTime(outputs[index].lastProducedAt)}</Tag><Button size="small" onClick={async()=>{setProductionOutput(outputs[index]);setProductions(await getDatasetProductions(outputs[index].id));}}>查看产出记录</Button></Space>}
          </Card>)}
          <Button type="dashed" icon={<PlusOutlined/>} onClick={()=>add({catalogName:'rtdwh_paimon',databaseName:'ads',layer:'ads',slaMinutes:1440,qualityGateEnabled:false})}>添加产出数据资源</Button>
        </Space>}</Form.List>
      </Form>
    </Modal>

    <Modal title={`产出记录：${productionOutput?.databaseName}.${productionOutput?.tableName}`} open={!!productionOutput} onCancel={()=>setProductionOutput(undefined)} footer={null}>
      <Table rowKey="id" dataSource={productions} pagination={false} columns={[{title:'业务日期',dataIndex:'businessDate'},{title:'实例',dataIndex:'instanceId'},{title:'状态',dataIndex:'status',render:(v)=><Tag color={v==='available'?'success':'error'}>{v}</Tag>},{title:'产出时间',dataIndex:'producedAt',render:formatTime}]} />
    </Modal>
  </PageContainer>;
};

export default Workflow;

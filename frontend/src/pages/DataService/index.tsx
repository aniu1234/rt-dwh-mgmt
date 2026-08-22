import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, DatePicker, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { ApiOutlined, KeyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import {
  createDataService, createDataServiceApp, deleteDataService, getDataServiceApps, getDataServiceGrants,
  getDataServiceLogs, getDataServices, grantDataService, publishDataService, revokeDataService,
  rotateDataServiceSecret, toggleDataServiceApp, updateDataService,
} from '@/api';

const parameterTemplate = JSON.stringify({ parameters: [
  { name: 'start_date', label: '开始日期', type: 'date', required: true },
  { name: 'region', label: '区域', type: 'string', defaultValue: '华东' },
] }, null, 2);

const DataService: React.FC = () => {
  const servicesRequest = useRequest(getDataServices);
  const appsRequest = useRequest(getDataServiceApps);
  const logsRequest = useRequest(() => getDataServiceLogs(200));
  const services = (servicesRequest.data || []) as API.DataServiceDefinition[];
  const apps = (appsRequest.data || []) as API.DataServiceApp[];
  const [editing, setEditing] = useState<API.DataServiceDefinition>();
  const [serviceOpen, setServiceOpen] = useState(false);
  const [credential, setCredential] = useState<API.DataServiceCredential>();
  const [grantApp, setGrantApp] = useState<API.DataServiceApp>();
  const [grants, setGrants] = useState<API.DataServiceGrant[]>([]);
  const [form] = Form.useForm();
  const [appForm] = Form.useForm();
  const [appOpen, setAppOpen] = useState(false);

  const editService = (value?: API.DataServiceDefinition) => {
    setEditing(value); setServiceOpen(true); form.resetFields();
    form.setFieldsValue(value || { catalogName: 'rtdwh_paimon', databaseName: 'ads', maxRows: 1000, timeoutSeconds: 30, rateLimitPerMinute: 60 });
  };
  const openGrants = async (app: API.DataServiceApp) => { setGrantApp(app); setGrants(await getDataServiceGrants(app.id)); };
  const refresh = () => { servicesRequest.refresh(); appsRequest.refresh(); logsRequest.refresh(); };

  return <PageContainer title="数据服务" subTitle="将 Doris 查询安全发布为外部系统可调用的数据接口" extra={<Button icon={<ReloadOutlined/>} onClick={refresh}>刷新</Button>}>
    <Alert type="info" showIcon style={{marginBottom:16}} message="外部调用地址：POST /api/v1/open/data/{serviceCode}" description="请求头携带 X-App-Key、X-App-Secret，请求体传入 JSON 参数；密钥只在创建或轮换时展示一次。" />
    <Card><Tabs items={[
      {key:'services',label:`服务定义（${services.length}）`,children:<>
        <Button type="primary" icon={<PlusOutlined/>} style={{marginBottom:16}} onClick={()=>editService()}>新建数据服务</Button>
        <Table rowKey="id" dataSource={services} loading={servicesRequest.loading} scroll={{x:1200}} columns={[
          {title:'服务',key:'name',width:220,render:(_,r)=><div><b>{r.serviceName}</b><div><Typography.Text code>{r.serviceCode}</Typography.Text></div></div>},
          {title:'数据源',key:'source',width:220,render:(_,r)=>`${r.catalogName}.${r.databaseName}`},
          {title:'版本',dataIndex:'apiVersion',width:80,render:v=>`v${v}`},
          {title:'限制',key:'limit',width:210,render:(_,r)=>`${r.maxRows} 行 · ${r.timeoutSeconds}s · ${r.rateLimitPerMinute}次/分`},
          {title:'状态',dataIndex:'status',width:100,render:v=><Tag color={v==='published'?'success':v==='offline'?'warning':'default'}>{v}</Tag>},
          {title:'接口',dataIndex:'serviceCode',ellipsis:true,render:v=><Typography.Text copyable code>{`/api/v1/open/data/${v}`}</Typography.Text>},
          {title:'操作',width:240,fixed:'right' as const,render:(_,r)=><Space><Button size="small" onClick={()=>editService(r)}>编辑</Button><Button size="small" type="primary" ghost={r.status==='published'} onClick={async()=>{await publishDataService(r.id,r.status!=='published');message.success(r.status==='published'?'已下线':'已发布');servicesRequest.refresh();}}>{r.status==='published'?'下线':'发布'}</Button><Popconfirm title="确认删除？" onConfirm={async()=>{await deleteDataService(r.id);message.success('已删除');servicesRequest.refresh();}}><Button size="small" danger>删除</Button></Popconfirm></Space>},
        ]}/>
      </>},
      {key:'apps',label:`调用应用（${apps.length}）`,children:<>
        <Button type="primary" icon={<KeyOutlined/>} style={{marginBottom:16}} onClick={()=>{setAppOpen(true);appForm.resetFields();}}>创建调用应用</Button>
        <Table rowKey="id" dataSource={apps} loading={appsRequest.loading} columns={[
          {title:'应用名称',dataIndex:'appName'},
          {title:'AppKey',dataIndex:'appKey',render:v=><Typography.Text copyable code>{v}</Typography.Text>},
          {title:'状态',dataIndex:'enabled',width:100,render:v=><Tag color={v?'success':'default'}>{v?'启用':'停用'}</Tag>},
          {title:'有效期',dataIndex:'expiresAt',render:v=>v||'长期有效'},
          {title:'操作',width:300,render:(_,r)=><Space><Button size="small" onClick={()=>openGrants(r)}>服务授权</Button><Popconfirm title="轮换后旧密钥立即失效，确认继续？" onConfirm={async()=>setCredential(await rotateDataServiceSecret(r.id))}><Button size="small">轮换密钥</Button></Popconfirm><Button size="small" danger={r.enabled} onClick={async()=>{await toggleDataServiceApp(r.id);appsRequest.refresh();}}>{r.enabled?'停用':'启用'}</Button></Space>},
        ]}/>
      </>},
      {key:'logs',label:'调用日志',children:<Table rowKey="id" dataSource={(logsRequest.data||[]) as API.DataServiceInvocationLog[]} loading={logsRequest.loading} scroll={{x:1100}} columns={[
        {title:'时间',dataIndex:'createdAt',width:180},{title:'服务',dataIndex:'serviceCode',width:180},{title:'应用ID',dataIndex:'appId',width:90},{title:'状态',dataIndex:'status',width:90,render:v=><Tag color={v==='success'?'success':'error'}>{v}</Tag>},{title:'HTTP',dataIndex:'httpStatus',width:80},{title:'行数',dataIndex:'rowCount',width:80},{title:'耗时',dataIndex:'durationMs',width:100,render:v=>v==null?'—':`${v}ms`},{title:'来源IP',dataIndex:'clientIp',width:140},{title:'错误',dataIndex:'errorMessage',ellipsis:true},
      ]}/>} ]}/></Card>

    <Modal title={editing?'编辑数据服务':'新建数据服务'} width={820} open={serviceOpen} onCancel={()=>setServiceOpen(false)} onOk={()=>form.submit()} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={async values=>{if(editing)await updateDataService(editing.id,values);else await createDataService(values);message.success(editing?'服务已更新':'服务已创建');setServiceOpen(false);servicesRequest.refresh();}}>
        <Space style={{width:'100%'}} align="start"><Form.Item name="serviceCode" label="服务编码" rules={[{required:true},{pattern:/^[a-z][a-z0-9_-]{2,63}$/}]}><Input disabled={!!editing} style={{width:240}} placeholder="order-summary"/></Form.Item><Form.Item name="serviceName" label="服务名称" rules={[{required:true}]}><Input style={{width:300}}/></Form.Item></Space>
        <Form.Item name="description" label="说明"><Input/></Form.Item>
        <Space align="start"><Form.Item name="catalogName" label="Catalog" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="databaseName" label="Database" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="maxRows" label="最大行数"><InputNumber min={1} max={50000}/></Form.Item><Form.Item name="timeoutSeconds" label="超时秒数"><InputNumber min={1} max={1800}/></Form.Item><Form.Item name="rateLimitPerMinute" label="每分钟限流"><InputNumber min={1}/></Form.Item></Space>
        <Form.Item name="sqlTemplate" label="只读 SQL 模板" extra={'值参数使用 {{name}}，不支持动态表名'} rules={[{required:true}]}><Input.TextArea rows={7} spellCheck={false}/></Form.Item>
        <Form.Item label="参数定义（JSON）" extra={<Button size="small" onClick={()=>form.setFieldValue('parameterConfig',parameterTemplate)}>一键代入模板</Button>} name="parameterConfig"><Input.TextArea rows={6} spellCheck={false} placeholder={parameterTemplate}/></Form.Item>
      </Form>
    </Modal>

    <Modal title="创建调用应用" open={appOpen} onCancel={()=>setAppOpen(false)} onOk={()=>appForm.submit()} destroyOnClose><Form form={appForm} layout="vertical" onFinish={async values=>{setCredential(await createDataServiceApp({...values,expiresAt:values.expiresAt?.format('YYYY-MM-DDTHH:mm:ss')}));setAppOpen(false);appsRequest.refresh();}}><Form.Item name="appName" label="应用名称" rules={[{required:true}]}><Input placeholder="订单系统"/></Form.Item><Form.Item name="expiresAt" label="凭证有效期" extra="不填写表示长期有效"><DatePicker showTime style={{width:'100%'}} placeholder="选择失效时间" /></Form.Item></Form></Modal>
    <Modal title="应用凭证（仅展示一次）" open={!!credential} onCancel={()=>setCredential(undefined)} footer={<Button type="primary" onClick={()=>setCredential(undefined)}>我已保存</Button>}><Alert type="warning" showIcon message="关闭后无法再次查看 AppSecret" style={{marginBottom:16}}/><Typography.Paragraph copyable><b>AppKey：</b>{credential?.appKey}</Typography.Paragraph><Typography.Paragraph copyable><b>AppSecret：</b>{credential?.appSecret}</Typography.Paragraph></Modal>
    <Modal title={`服务授权：${grantApp?.appName||''}`} open={!!grantApp} onCancel={()=>setGrantApp(undefined)} footer={null}>
      <Select style={{width:'100%',marginBottom:16}} placeholder="选择要授权的数据服务" options={services.filter(s=>!grants.some(g=>g.serviceId===s.id)).map(s=>({value:s.id,label:`${s.serviceName}（${s.serviceCode}）`}))} onChange={async serviceId=>{if(!grantApp)return;await grantDataService(grantApp.id,serviceId);setGrants(await getDataServiceGrants(grantApp.id));message.success('授权已添加');}}/>
      <Table rowKey="id" dataSource={grants} pagination={false} columns={[{title:'已授权服务',dataIndex:'serviceId',render:id=>services.find(s=>s.id===id)?.serviceName||id},{title:'操作',width:100,render:(_,g)=><Button danger type="link" onClick={async()=>{if(!grantApp)return;await revokeDataService(grantApp.id,g.serviceId);setGrants(await getDataServiceGrants(grantApp.id));}}>移除</Button>}]} />
    </Modal>
  </PageContainer>;
};

export default DataService;

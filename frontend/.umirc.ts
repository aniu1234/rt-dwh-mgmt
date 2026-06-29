import { defineConfig } from '@umijs/max';

export default defineConfig({
  antd: {},
  access: {},
  model: {},
  initialState: {},
  request: {},
  layout: {
    title: '实时数仓管理平台',
    locale: true,
    navTheme: 'dark',
    token: {
      sider: {
        colorBgMenuItemSelected: '#1890ff',
        colorBgMenuItemHover: 'rgba(255,255,255,0.08)',
      },
      header: {
        colorBgMenuItemSelected: '#1890ff',
        colorBgMenuItemHover: 'rgba(255,255,255,0.08)',
      },
    },
  },
  locale: {
    default: 'zh-CN',
    baseSeparator: '-',
    antd: true,
  },
  hash: true,
  jsMinifier: 'terser',
  plugins: [],
  proxy: {
    '/api/v1': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      pathRewrite: { '^/api/v1': '' },
    },
  },
  routes: [
    {
      path: '/user',
      layout: false,
      routes: [
        { path: '/user/login', component: './User/Login' },
      ],
    },
    // === 总览 ===
    {
      path: '/dashboard',
      name: '数据总览',
      icon: 'DashboardOutlined',
      component: './Dashboard',
    },
    // === 同步任务 ===
    {
      path: '/sync-task',
      name: '同步任务',
      icon: 'ThunderboltOutlined',
      routes: [
        { path: '/sync-task/list', name: '任务管理', icon: 'UnorderedListOutlined', component: './SyncTask/List' },
        { path: '/sync-task/create', name: '创建任务', icon: 'PlusOutlined', component: './SyncTask/Create', hideInMenu: true },
        { path: '/sync-task/detail/:id', name: '任务详情', icon: 'ProfileOutlined', component: './SyncTask/Detail', hideInMenu: true },
        { path: '/sync-task/datasource', name: '数据源配置', icon: 'ApiOutlined', component: './Datasource' },
      ],
    },
    // === 数仓管理 ===
    {
      path: '/dwh',
      name: '数仓管理',
      icon: 'DatabaseOutlined',
      routes: [
        { path: '/dwh/tables', name: '表管理', icon: 'TableOutlined', component: './DwhTable/List' },
        { path: '/dwh/tables/:id', name: '表详情', icon: 'ProfileOutlined', component: './DwhTable/Detail', hideInMenu: true },
        { path: '/dwh/lineage', name: '数据血缘', icon: 'ApartmentOutlined', component: './Lineage' },
        { path: '/dwh/maintenance', name: '表维护', icon: 'ToolOutlined', component: './Maintenance' },
      ],
    },
    // === 数据质量 ===
    {
      path: '/quality',
      name: '数据质量',
      icon: 'CheckCircleOutlined',
      component: './Quality',
    },
    // === 查询与报表 ===
    {
      path: '/query',
      name: '查询与报表',
      icon: 'SearchOutlined',
      routes: [
        { path: '/query/adhoc', name: '即席查询', icon: 'CodeOutlined', component: './AdhocQuery' },
        { path: '/query/report', name: '报表看板', icon: 'BarChartOutlined', component: './Report' },
      ],
    },
    // === 告警与系统 ===
    {
      path: '/system',
      name: '告警与系统',
      icon: 'AlertOutlined',
      routes: [
        { path: '/system/alert', name: '告警管理', icon: 'BellOutlined', component: './Alert' },
        { path: '/system/settings', name: '系统设置', icon: 'SettingOutlined', component: './Settings' },
      ],
    },
  ],
  theme: {
    'primary-color': '#1a73e8',
  },
});

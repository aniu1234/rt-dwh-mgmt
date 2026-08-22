import { defineConfig } from '@umijs/max';

export default defineConfig({
  antd: {
    configProvider: {
      theme: {
        token: {
          colorBgBase: '#ffffff',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorTextBase: '#333333',
          colorBorder: '#d9d9d9',
          colorBorderSecondary: '#f0f0f0',
          colorFillAlter: '#fafafa',
        },
        components: {
          Table: {
            headerBg: '#fafafa',
            headerColor: '#344054',
            rowHoverBg: '#e6f7ff',
            borderColor: '#f0f0f0',
          },
          Input: {
            activeBg: '#ffffff',
            hoverBg: '#ffffff',
          },
          Select: {
            selectorBg: '#ffffff',
            optionActiveBg: '#e6f7ff',
            optionSelectedBg: '#e6f7ff',
          },
          Button: {
            defaultBg: '#ffffff',
            defaultColor: '#333333',
          },
        },
      },
    },
  },
  access: {},
  model: {},
  initialState: {},
  // app.tsx already unwraps the backend's ApiResponse.data globally.
  // Keep useRequest from applying its default result => result.data a
  // second time, which otherwise turns arrays and business objects into
  // undefined across list, dashboard, settings, quality and report pages.
  request: {
    dataField: '',
  },
  layout: {
    layout: 'side',
    title: '实时数仓管理平台',
    locale: true,
    navTheme: 'dark',
    token: {
      sider: {
        colorBgMenuItemSelected: '#1890ff',
        colorBgMenuItemHover: 'rgba(255,255,255,0.08)',
      },
      header: {
        heightLayoutHeader: 48,
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
  metas: [
    { name: 'color-scheme', content: 'only light' },
  ],
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
    {
      path: '/foundation',
      name: '公共能力',
      icon: 'SafetyCertificateOutlined',
      component: './Foundation',
      access: 'canViewFoundation',
    },
    // === 同步任务 ===
    {
      path: '/sync-task',
      name: '同步任务',
      icon: 'ThunderboltOutlined',
      routes: [
        { path: '/sync-task/list', name: '任务管理', icon: 'UnorderedListOutlined', component: './SyncTask/List', access: 'canViewTask' },
        { path: '/sync-task/workflow', name: '任务编排', icon: 'ApartmentOutlined', component: './Workflow', access: 'canViewTask' },
        { path: '/sync-task/create', name: '创建任务', icon: 'PlusOutlined', component: './SyncTask/Create', hideInMenu: true, access: 'canCreateTask' },
        { path: '/sync-task/detail/:id', name: '任务详情', icon: 'ProfileOutlined', component: './SyncTask/Detail', hideInMenu: true, access: 'canViewTask' },
        { path: '/sync-task/datasource', name: '数据源配置', icon: 'ApiOutlined', component: './Datasource', access: 'canViewDatasource' },
      ],
    },
    // === 数仓管理 ===
    {
      path: '/dwh',
      name: '数仓管理',
      icon: 'DatabaseOutlined',
      routes: [
        { path: '/dwh/tables', name: '表管理', icon: 'TableOutlined', component: './DwhTable/List', access: 'canViewDwh' },
        { path: '/dwh/tables/:id', name: '表详情', icon: 'ProfileOutlined', component: './DwhTable/Detail', hideInMenu: true, access: 'canViewDwh' },
        { path: '/dwh/lineage', name: '数据血缘', icon: 'ApartmentOutlined', component: './Lineage', access: 'canViewLineage' },
        { path: '/dwh/maintenance', name: '表维护', icon: 'ToolOutlined', component: './Maintenance', access: 'canManageDwh' },
      ],
    },
    // === 数据质量 ===
    {
      path: '/quality',
      name: '数据质量',
      icon: 'CheckCircleOutlined',
      component: './Quality',
      access: 'canViewQuality',
    },
    // === 查询与报表 ===
    {
      path: '/query',
      name: '查询与报表',
      icon: 'SearchOutlined',
      routes: [
        { path: '/query/adhoc', name: '即席查询', icon: 'CodeOutlined', component: './AdhocQuery', access: 'canQuery' },
        { path: '/query/report', name: '报表看板', icon: 'BarChartOutlined', component: './Report', access: 'canViewReport' },
        { path: '/query/data-service', name: '数据服务', icon: 'ApiOutlined', component: './DataService', access: 'canViewDataService' },
      ],
    },
    // === 告警与系统 ===
    {
      path: '/system',
      name: '告警与系统',
      icon: 'AlertOutlined',
      routes: [
        { path: '/system/alert', name: '告警管理', icon: 'BellOutlined', component: './Alert', access: 'canViewAlert' },
        { path: '/system/settings', name: '系统设置', icon: 'SettingOutlined', component: './Settings', access: 'canViewSettings' },
        { path: '/system/users', name: '用户与权限', icon: 'TeamOutlined', component: './UserAdmin', access: 'canManageUser' },
        { path: '/system/audit', name: '操作审计', icon: 'AuditOutlined', component: './Audit', access: 'canViewAudit' },
      ],
    },
  ],
  theme: {
    'primary-color': '#1890ff',
  },
});

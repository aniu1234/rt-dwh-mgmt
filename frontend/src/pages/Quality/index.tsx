import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Badge, Button, Card, Col, Empty, Form, Input, InputNumber, message, Modal,
  Popconfirm, Progress, Row, Select, Space, Statistic, Switch, Table, Tabs, Tag, Tooltip,
} from 'antd';
import {
  CheckCircleOutlined, ClockCircleOutlined, DatabaseOutlined,
  PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, WarningOutlined,
} from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import dayjs from 'dayjs';
import {
  createQualityRule, deleteQualityRule, getQualityAlerts, getQualityRules,
  getQualityRuns, resolveQualityAlert, runQualityCheck, toggleQualityRule, updateQualityRule,
} from '@/api';
import './index.less';

const layers = ['ods', 'dwd', 'dws', 'ads'];

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const ruleTypeLabel: Record<string, string> = {
  null_rate: '空值率',
  uniqueness: '唯一性',
  volume_compare: '数据量',
  range_check: '范围检查',
};

const levelConfig: Record<string, { color: string; label: string; weight: number }> = {
  info: { color: 'blue', label: '提示', weight: 1 },
  low: { color: 'blue', label: '提示', weight: 1 },
  warn: { color: 'orange', label: '警告', weight: 2 },
  medium: { color: 'orange', label: '警告', weight: 2 },
  error: { color: 'red', label: '严重', weight: 3 },
  high: { color: 'red', label: '严重', weight: 3 },
};

const runStatusConfig: Record<string, { color: string; label: string }> = {
  passed: { color: 'green', label: '通过' },
  failed: { color: 'red', label: '未通过' },
  error: { color: 'volcano', label: '执行异常' },
  running: { color: 'blue', label: '执行中' },
};

const triggerTypeLabel: Record<string, string> = {
  manual: '手动', scheduled: '定时', production: '产出联动',
};

const toPercent = (value: number, total: number) => total ? Math.round((value / total) * 100) : 0;

const formatDateTime = (value?: string) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';

const formatNumber = (value?: number) => {
  if (value == null) return '—';
  if (Number.isInteger(value)) return value.toLocaleString('zh-CN');
  return value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
};

const Quality: React.FC = () => {
  const access = useAccess();
  const [activeTab, setActiveTab] = useState('overview');
  const [layerFilter, setLayerFilter] = useState<string>();
  const [ruleTypeFilter, setRuleTypeFilter] = useState<string>();
  const [ruleSearch, setRuleSearch] = useState('');
  const [alertLevel, setAlertLevel] = useState<string>();
  const [alertResolved, setAlertResolved] = useState<boolean>();
  const [runStatus, setRunStatus] = useState<string>();
  const [runTrigger, setRunTrigger] = useState<string>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<API.QualityRule>();
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form] = Form.useForm();

  const {
    data: rulesData,
    loading: rulesLoading,
    refresh: refreshRules,
  } = useRequest(() => getQualityRules());
  const {
    data: alertsData,
    loading: alertsLoading,
    refresh: refreshAlerts,
  } = useRequest(() => getQualityAlerts());
  const { data: runsData, loading: runsLoading, refresh: refreshRuns } = useRequest(getQualityRuns);

  const rules = (rulesData || []) as API.QualityRule[];
  const alerts = (alertsData || []) as API.QualityAlert[];
  const runs = (runsData || []) as API.QualityCheckRun[];

  const latestRunByRule = useMemo(() => {
    const map = new Map<number, API.QualityCheckRun>();
    runs.forEach((run) => {
      const current = map.get(run.ruleId);
      if (!current || dayjs(run.startedAt).valueOf() > dayjs(current.startedAt).valueOf()) {
        map.set(run.ruleId, run);
      }
    });
    return map;
  }, [runs]);

  const metrics = useMemo(() => {
    const enabledRules = rules.filter((rule) => rule.enabled);
    const checkedRules = enabledRules.filter((rule) => latestRunByRule.has(rule.id));
    const passedRules = checkedRules.filter((rule) => latestRunByRule.get(rule.id)?.status === 'passed');
    const unresolvedAlerts = alerts.filter((alert) => !alert.resolved);
    const severeAlerts = unresolvedAlerts.filter((alert) => (levelConfig[alert.level]?.weight || 1) >= 3);
    const resolvedAlerts = alerts.filter((alert) => alert.resolved);
    const last24hRuns = runs.filter((run) => dayjs(run.startedAt).isAfter(dayjs().subtract(24, 'hour')));
    const completedRuns = runs.filter((run) => run.durationMs != null && run.status !== 'running');
    const enabledRate = toPercent(enabledRules.length, rules.length);
    const checkCoverage = toPercent(checkedRules.length, enabledRules.length);
    const passRate = toPercent(passedRules.length, checkedRules.length);
    const alertResolutionRate = alerts.length ? toPercent(resolvedAlerts.length, alerts.length) : 100;
    const healthScore = enabledRules.length
      ? Math.round(passRate * 0.5 + checkCoverage * 0.3 + alertResolutionRate * 0.2)
      : 0;
    const avgDuration = completedRuns.length
      ? Math.round(completedRuns.reduce((sum, run) => sum + (run.durationMs || 0), 0) / completedRuns.length)
      : 0;

    return {
      enabledRules,
      checkedRules,
      unresolvedAlerts,
      severeAlerts,
      enabledRate,
      checkCoverage,
      passRate,
      healthScore,
      avgDuration,
      last24hRuns: last24hRuns.length,
      coveredTables: new Set(enabledRules.map((rule) => `${rule.layer}.${rule.targetTable}`)).size,
      neverChecked: enabledRules.length - checkedRules.length,
      disabledRules: rules.length - enabledRules.length,
    };
  }, [alerts, latestRunByRule, rules, runs]);

  const layerStats = useMemo(() => layers.map((layer) => {
    const layerRules = rules.filter((rule) => rule.layer?.toLowerCase() === layer);
    const enabledRules = layerRules.filter((rule) => rule.enabled);
    const checkedRules = enabledRules.filter((rule) => latestRunByRule.has(rule.id));
    const passedRules = checkedRules.filter((rule) => latestRunByRule.get(rule.id)?.status === 'passed');
    const ruleIds = new Set(layerRules.map((rule) => rule.id));
    const unresolvedAlerts = alerts.filter((alert) => !alert.resolved && ruleIds.has(alert.ruleId));
    return {
      layer,
      ruleCount: layerRules.length,
      tableCount: new Set(enabledRules.map((rule) => rule.targetTable)).size,
      checkedCount: checkedRules.length,
      passRate: toPercent(passedRules.length, checkedRules.length),
      unresolvedCount: unresolvedAlerts.length,
    };
  }), [alerts, latestRunByRule, rules]);

  const trendData = useMemo(() => Array.from({ length: 7 }, (_, index) => {
    const date = dayjs().subtract(6 - index, 'day');
    const dayRuns = runs.filter((run) => dayjs(run.startedAt).isSame(date, 'day'));
    const passed = dayRuns.filter((run) => run.status === 'passed').length;
    const abnormal = dayRuns.filter((run) => run.status === 'failed' || run.status === 'error').length;
    return {
      key: date.format('YYYY-MM-DD'),
      label: date.format('MM/DD'),
      total: dayRuns.length,
      passed,
      abnormal,
      passRate: toPercent(passed, passed + abnormal),
    };
  }), [runs]);

  const visibleRules = useMemo(() => {
    const keyword = ruleSearch.trim().toLowerCase();
    return rules.filter((rule) => {
      if (layerFilter && rule.layer !== layerFilter) return false;
      if (ruleTypeFilter && rule.ruleType !== ruleTypeFilter) return false;
      if (!keyword) return true;
      return [rule.ruleName, rule.targetTable, rule.targetColumn]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
    });
  }, [layerFilter, ruleSearch, ruleTypeFilter, rules]);

  const visibleAlerts = useMemo(() => alerts.filter((alert) => {
    if (alertLevel && alert.level !== alertLevel) return false;
    return alertResolved == null || alert.resolved === alertResolved;
  }), [alertLevel, alertResolved, alerts]);

  const visibleRuns = useMemo(() => runs.filter((run) => {
    if (runStatus && run.status !== runStatus) return false;
    return !runTrigger || run.triggerType === runTrigger;
  }), [runStatus, runTrigger, runs]);

  const priorityRisks = useMemo(() => [...metrics.unresolvedAlerts]
    .sort((left, right) => {
      const levelDiff = (levelConfig[right.level]?.weight || 1) - (levelConfig[left.level]?.weight || 1);
      return levelDiff || dayjs(right.triggeredAt).valueOf() - dayjs(left.triggeredAt).valueOf();
    })
    .slice(0, 5), [metrics.unresolvedAlerts]);

  const refreshAll = () => {
    refreshRules();
    refreshAlerts();
    refreshRuns();
  };

  const openRuleModal = (rule?: API.QualityRule) => {
    setEditingRule(rule);
    setModalOpen(true);
  };

  const saveRule = async (values: API.QualityRule) => {
    setSubmitting(true);
    try {
      if (editingRule) {
        await updateQualityRule(editingRule.id, values);
        message.success('质量规则已更新');
      } else {
        await createQualityRule(values);
        message.success('质量规则已创建');
      }
      setModalOpen(false);
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '保存质量规则失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRunCheck = async (ruleId?: number) => {
    setCheckingId(ruleId || -1);
    try {
      const count = await runQualityCheck(ruleId);
      message.success(`质量检查完成，发现 ${count || 0} 个异常`);
      refreshAlerts();
      refreshRuns();
    } catch (error: any) {
      message.error(error?.message || '质量检查失败');
    } finally {
      setCheckingId(undefined);
    }
  };

  const handleResolveAlert = async (id: number) => {
    try {
      await resolveQualityAlert(id);
      message.success('告警已标记为解决');
      refreshAlerts();
    } catch (error: any) {
      message.error(error?.message || '处理告警失败');
    }
  };

  const handleToggle = async (rule: API.QualityRule, enabled: boolean) => {
    try {
      await toggleQualityRule(rule.id, enabled);
      message.success(enabled ? '规则已启用' : '规则已停用');
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '更新规则状态失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteQualityRule(id);
      message.success('质量规则已删除');
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '删除质量规则失败');
    }
  };

  const ruleFilters = (
    <div className="rtdwh-toolbar quality-filter-bar">
      <Input.Search
        allowClear
        value={ruleSearch}
        onChange={(event) => setRuleSearch(event.target.value)}
        placeholder="搜索规则、表或字段"
        className="quality-rule-search"
      />
      <Select
        placeholder="数仓分层"
        allowClear
        value={layerFilter}
        onChange={setLayerFilter}
        options={layers.map((value) => ({ label: value.toUpperCase(), value }))}
      />
      <Select
        placeholder="规则类型"
        allowClear
        value={ruleTypeFilter}
        onChange={setRuleTypeFilter}
        options={Object.entries(ruleTypeLabel).map(([value, label]) => ({ value, label }))}
      />
    </div>
  );

  const overviewColumns: any[] = [
    {
      title: '目标表',
      dataIndex: 'targetTable',
      key: 'table',
      render: (value: string, record: API.QualityRule) => (
        <Space size={6}>
          <Tag color={layerColorMap[record.layer]}>{record.layer?.toUpperCase()}</Tag>
          <span>{value}</span>
        </Space>
      ),
    },
    { title: '质量规则', dataIndex: 'ruleName', key: 'name' },
    {
      title: '维度', dataIndex: 'ruleType', key: 'type', width: 110,
      render: (value: string) => ruleTypeLabel[value] || value,
    },
    { title: '阈值', dataIndex: 'threshold', key: 'threshold', width: 90, render: formatNumber },
    {
      title: '实际值', key: 'actualValue', width: 100,
      render: (_: unknown, record: API.QualityRule) => formatNumber(latestRunByRule.get(record.id)?.actualValue),
    },
    {
      title: '最近状态', key: 'status', width: 110,
      render: (_: unknown, record: API.QualityRule) => {
        const latestRun = latestRunByRule.get(record.id);
        if (!record.enabled) return <Badge status="default" text="未启用" />;
        if (!latestRun) return <Badge status="default" text="未检测" />;
        if (latestRun.status === 'running') return <Badge status="processing" text="检测中" />;
        if (latestRun.status === 'error') return <Badge status="error" text="执行异常" />;
        return latestRun.status === 'failed'
          ? <Badge status="error" text="未通过" /> : <Badge status="success" text="通过" />;
      },
    },
    {
      title: '最近检测', key: 'checkedAt', width: 170,
      render: (_: unknown, record: API.QualityRule) => formatDateTime(latestRunByRule.get(record.id)?.startedAt),
    },
    {
      title: '操作', key: 'action', width: 110, fixed: 'right',
      render: (_: unknown, record: API.QualityRule) => access.canManageQuality ? (
        <Button
          size="small"
          loading={checkingId === record.id}
          disabled={!record.enabled}
          onClick={() => handleRunCheck(record.id)}
        >
          立即检测
        </Button>
      ) : <span className="quality-muted">仅查看</span>,
    },
  ];

  return (
    <PageContainer
      title="数据质量治理"
      subTitle="覆盖规则、检测、告警与处置的质量闭环"
      className="quality-page"
      extra={(
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新数据</Button>
          {access.canManageQuality && (
            <Button type="primary" loading={checkingId === -1} onClick={() => handleRunCheck()}>
              立即检查全部
            </Button>
          )}
        </Space>
      )}
    >
      <Row gutter={[16, 16]} className="quality-metrics">
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card quality-health-metric">
            <span className="rtdwh-metric-icon quality-icon-blue"><SafetyCertificateOutlined /></span>
            <Statistic title="质量健康分" value={metrics.healthScore} suffix="分" />
            <div className="quality-metric-foot">通过率、检测覆盖和告警处置综合评估</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-green"><CheckCircleOutlined /></span>
            <Statistic title="最新检测通过率" value={metrics.passRate} suffix="%" />
            <div className="quality-metric-foot">已检测 {metrics.checkedRules.length} 条，待检测 {metrics.neverChecked} 条</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-purple"><DatabaseOutlined /></span>
            <Statistic title="质量覆盖数据表" value={metrics.coveredTables} suffix="张" />
            <div className="quality-metric-foot">启用 {metrics.enabledRules.length} / {rules.length} 条规则</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-red"><WarningOutlined /></span>
            <Statistic title="未解决告警" value={metrics.unresolvedAlerts.length} suffix="条" />
            <div className="quality-metric-foot">其中严重告警 {metrics.severeAlerts.length} 条</div>
          </Card>
        </Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'overview',
            label: '质量概览',
            children: (
              <>
                <Row gutter={[16, 16]}>
                  <Col xs={24} xl={9}>
                    <Card title="治理健康度" className="quality-panel-card">
                      <div className="quality-health-layout">
                        <Tooltip title="最新通过率 50% + 检测覆盖率 30% + 告警处置率 20%">
                          <Progress
                            type="dashboard"
                            percent={metrics.healthScore}
                            size={148}
                            strokeColor={metrics.healthScore >= 80 ? '#52c41a' : metrics.healthScore >= 60 ? '#faad14' : '#ff4d4f'}
                            format={(value) => <><strong>{value}</strong><small>质量分</small></>}
                          />
                        </Tooltip>
                        <div className="quality-progress-list">
                          <div>
                            <span>规则启用率</span><b>{metrics.enabledRate}%</b>
                            <Progress percent={metrics.enabledRate} showInfo={false} size="small" />
                          </div>
                          <div>
                            <span>检测覆盖率</span><b>{metrics.checkCoverage}%</b>
                            <Progress percent={metrics.checkCoverage} showInfo={false} size="small" strokeColor="#722ed1" />
                          </div>
                          <div>
                            <span>最新通过率</span><b>{metrics.passRate}%</b>
                            <Progress percent={metrics.passRate} showInfo={false} size="small" strokeColor="#52c41a" />
                          </div>
                        </div>
                      </div>
                      <div className="quality-health-summary">
                        <span><ClockCircleOutlined /> 近 24 小时检测 <b>{metrics.last24hRuns}</b> 次</span>
                        <span>平均耗时 <b>{metrics.avgDuration.toLocaleString('zh-CN')}</b> ms</span>
                      </div>
                    </Card>
                  </Col>
                  <Col xs={24} xl={15}>
                    <Card title="数仓分层质量" className="quality-panel-card">
                      <div className="quality-layer-grid">
                        {layerStats.map((item) => (
                          <button
                            type="button"
                            className="quality-layer-card"
                            key={item.layer}
                            onClick={() => setLayerFilter(item.layer)}
                          >
                            <div className="quality-layer-head">
                              <Tag color={layerColorMap[item.layer]}>{item.layer.toUpperCase()}</Tag>
                              <b className={item.unresolvedCount ? 'quality-danger' : 'quality-success'}>
                                {item.unresolvedCount ? `${item.unresolvedCount} 个异常` : '运行正常'}
                              </b>
                            </div>
                            <strong>{item.passRate}%</strong>
                            <span>最新通过率</span>
                            <Progress percent={item.passRate} showInfo={false} size="small" />
                            <div className="quality-layer-meta">
                              <span>{item.ruleCount} 条规则</span>
                              <span>{item.tableCount} 张表</span>
                              <span>{item.checkedCount} 条已检测</span>
                            </div>
                          </button>
                        ))}
                      </div>
                    </Card>
                  </Col>
                </Row>

                <Row gutter={[16, 16]} className="quality-section-row">
                  <Col xs={24} xl={14}>
                    <Card title="近 7 天检测趋势" className="quality-panel-card">
                      <div className="quality-trend-legend">
                        <span><i className="quality-dot quality-dot-success" />通过</span>
                        <span><i className="quality-dot quality-dot-danger" />异常</span>
                      </div>
                      <div className="quality-trend">
                        {trendData.map((item) => (
                          <div className="quality-trend-row" key={item.key}>
                            <span className="quality-trend-date">{item.label}</span>
                            <div className="quality-trend-track">
                              {item.total ? (
                                <>
                                  <i className="quality-trend-passed" style={{ width: `${toPercent(item.passed, item.total)}%` }} />
                                  <i className="quality-trend-failed" style={{ width: `${toPercent(item.abnormal, item.total)}%` }} />
                                </>
                              ) : <span className="quality-trend-empty">当日无检测</span>}
                            </div>
                            <span className="quality-trend-value">{item.total} 次 / {item.passRate}%</span>
                          </div>
                        ))}
                      </div>
                    </Card>
                  </Col>
                  <Col xs={24} xl={10}>
                    <Card
                      title="待处理风险"
                      className="quality-panel-card"
                      extra={metrics.unresolvedAlerts.length > 0 && <Button type="link" onClick={() => setActiveTab('alerts')}>查看全部</Button>}
                    >
                      {priorityRisks.length ? (
                        <div className="quality-risk-list">
                          {priorityRisks.map((alert) => (
                            <button type="button" key={alert.id} onClick={() => setActiveTab('alerts')}>
                              <Tag color={levelConfig[alert.level]?.color || 'default'}>
                                {levelConfig[alert.level]?.label || alert.level}
                              </Tag>
                              <span className="quality-risk-main">
                                <b>{alert.targetTable}</b>
                                <small>{alert.message}</small>
                              </span>
                              <time>{dayjs(alert.triggeredAt).format('MM-DD HH:mm')}</time>
                            </button>
                          ))}
                        </div>
                      ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待处理质量风险" />}
                    </Card>
                  </Col>
                </Row>

                {(metrics.neverChecked > 0 || metrics.disabledRules > 0) && (
                  <Alert
                    className="quality-governance-tip"
                    type="warning"
                    showIcon
                    message="质量覆盖仍有提升空间"
                    description={`当前有 ${metrics.neverChecked} 条启用规则尚未检测，${metrics.disabledRules} 条规则处于停用状态。建议先补齐检测覆盖，再处理失败规则。`}
                  />
                )}

                <Card title="规则运行状态" className="quality-rule-card">
                  {ruleFilters}
                  <Table<API.QualityRule>
                    dataSource={visibleRules}
                    rowKey="id"
                    loading={rulesLoading}
                    size="small"
                    columns={overviewColumns}
                    locale={{ emptyText: '暂无匹配的质量规则' }}
                    pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
                    scroll={{ x: 980 }}
                  />
                </Card>
              </>
            ),
          },
          {
            key: 'alerts',
            label: <Badge count={metrics.unresolvedAlerts.length} size="small" offset={[8, -2]}>异常告警</Badge>,
            children: (
              <Card>
                <div className="rtdwh-toolbar quality-filter-bar">
                  <Select
                    placeholder="告警级别"
                    allowClear
                    value={alertLevel}
                    onChange={setAlertLevel}
                    options={[
                      { value: 'error', label: '严重' },
                      { value: 'warn', label: '警告' },
                      { value: 'info', label: '提示' },
                    ]}
                  />
                  <Select
                    placeholder="处置状态"
                    allowClear
                    value={alertResolved}
                    onChange={setAlertResolved}
                    options={[{ label: '未解决', value: false }, { label: '已解决', value: true }]}
                  />
                  <span className="quality-filter-result">筛选结果 {visibleAlerts.length} 条</span>
                </div>
                <Table<API.QualityAlert>
                  dataSource={visibleAlerts}
                  rowKey="id"
                  loading={alertsLoading}
                  size="small"
                  columns={[
                    { title: '发生时间', dataIndex: 'triggeredAt', width: 170, render: formatDateTime },
                    { title: '目标表', dataIndex: 'targetTable', width: 180 },
                    { title: '告警内容', dataIndex: 'message', ellipsis: true },
                    {
                      title: '实际值 / 阈值', width: 140,
                      render: (_, record) => `${formatNumber(record.actualValue)} / ${formatNumber(record.thresholdValue)}`,
                    },
                    {
                      title: '级别', dataIndex: 'level', width: 90,
                      render: (value) => <Tag color={levelConfig[value]?.color}>{levelConfig[value]?.label || value}</Tag>,
                    },
                    {
                      title: '处置状态', width: 100,
                      render: (_, record) => <Badge status={record.resolved ? 'success' : 'error'} text={record.resolved ? '已解决' : '待处理'} />,
                    },
                    {
                      title: '操作', width: 120, fixed: 'right',
                      render: (_, record) => access.canManageQuality && !record.resolved ? (
                        <Button size="small" type="primary" onClick={() => handleResolveAlert(record.id)}>标记已解决</Button>
                      ) : <span className="quality-muted">—</span>,
                    },
                  ]}
                  locale={{ emptyText: '暂无匹配的质量告警' }}
                  pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
                  scroll={{ x: 980 }}
                />
              </Card>
            ),
          },
          {
            key: 'runs',
            label: '检测记录',
            children: (
              <Card>
                <div className="rtdwh-toolbar quality-filter-bar">
                  <Select
                    placeholder="检测状态"
                    allowClear
                    value={runStatus}
                    onChange={setRunStatus}
                    options={Object.entries(runStatusConfig).map(([value, config]) => ({ value, label: config.label }))}
                  />
                  <Select
                    placeholder="触发方式"
                    allowClear
                    value={runTrigger}
                    onChange={setRunTrigger}
                    options={Object.entries(triggerTypeLabel).map(([value, label]) => ({ value, label }))}
                  />
                  <span className="quality-filter-result">最近保留 100 条，当前 {visibleRuns.length} 条</span>
                </div>
                <Table<API.QualityCheckRun>
                  dataSource={visibleRuns}
                  rowKey="id"
                  loading={runsLoading}
                  size="small"
                  columns={[
                    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: formatDateTime },
                    { title: '质量规则', dataIndex: 'ruleName', width: 190 },
                    {
                      title: '触发方式', dataIndex: 'triggerType', width: 100,
                      render: (value) => triggerTypeLabel[value] || value,
                    },
                    { title: '引擎', dataIndex: 'engine', width: 80, render: (value) => <Tag color="blue">{value}</Tag> },
                    {
                      title: '实际值 / 阈值', width: 140,
                      render: (_, record) => `${formatNumber(record.actualValue)} / ${formatNumber(record.thresholdValue)}`,
                    },
                    {
                      title: '耗时', dataIndex: 'durationMs', width: 100,
                      render: (value) => value == null ? '—' : `${Number(value).toLocaleString('zh-CN')} ms`,
                    },
                    {
                      title: '状态', dataIndex: 'status', width: 100,
                      render: (value: API.QualityCheckRun['status']) => (
                        <Tag color={runStatusConfig[value]?.color || 'default'}>{runStatusConfig[value]?.label || value}</Tag>
                      ),
                    },
                    { title: '错误信息', dataIndex: 'errorMessage', ellipsis: true, render: (value) => value || '—' },
                  ]}
                  expandable={{ expandedRowRender: (record) => <pre className="quality-sql-preview">{record.checkSql}</pre> }}
                  locale={{ emptyText: '暂无匹配的检测记录' }}
                  pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
                  scroll={{ x: 1120 }}
                />
              </Card>
            ),
          },
          {
            key: 'rules',
            label: '规则管理',
            children: (
              <Card>
                <div className="quality-rule-toolbar">
                  {ruleFilters}
                  {access.canManageQuality && (
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => openRuleModal()}>
                      新建质量规则
                    </Button>
                  )}
                </div>
                <Table<API.QualityRule>
                  dataSource={visibleRules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: '规则名称', dataIndex: 'ruleName', width: 180 },
                    {
                      title: '分层', dataIndex: 'layer', width: 80,
                      render: (value) => <Tag color={layerColorMap[value]}>{String(value).toUpperCase()}</Tag>,
                    },
                    { title: '目标表', dataIndex: 'targetTable' },
                    { title: '目标字段', dataIndex: 'targetColumn', render: (value) => value || '全表' },
                    { title: '质量维度', dataIndex: 'ruleType', render: (value) => ruleTypeLabel[value] || value },
                    { title: '阈值', dataIndex: 'threshold', width: 90, render: formatNumber },
                    {
                      title: '启用', dataIndex: 'enabled', width: 80,
                      render: (enabled, record) => (
                        <Switch
                          size="small"
                          checked={enabled}
                          disabled={!access.canManageQuality}
                          onChange={(checked) => handleToggle(record, checked)}
                        />
                      ),
                    },
                    {
                      title: '操作', width: 140, fixed: 'right',
                      render: (_, record) => access.canManageQuality ? (
                        <Space size={4}>
                          <Button size="small" type="link" onClick={() => openRuleModal(record)}>编辑</Button>
                          <Popconfirm title="确定删除这条质量规则？" onConfirm={() => handleDelete(record.id)}>
                            <Button size="small" type="link" danger>删除</Button>
                          </Popconfirm>
                        </Space>
                      ) : <span className="quality-muted">仅查看</span>,
                    },
                  ]}
                  locale={{ emptyText: '暂无匹配的质量规则' }}
                  pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
                  scroll={{ x: 900 }}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingRule ? '编辑质量规则' : '新建质量规则'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnHidden
        afterOpenChange={(open) => {
          if (open) {
            form.resetFields();
            form.setFieldsValue(editingRule || { enabled: true, ruleType: 'null_rate', layer: 'ods' });
          }
        }}
      >
        <Form form={form} layout="vertical" onFinish={saveRule}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：订单 ID 唯一性" />
          </Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item name="layer" label="数仓分层" rules={[{ required: true, message: '请选择数仓分层' }]}>
                <Select options={layers.map((value) => ({ label: value.toUpperCase(), value }))} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
                <Select options={Object.entries(ruleTypeLabel).map(([value, label]) => ({ value, label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: true, message: '请输入目标表' }]}>
            <Input placeholder="例如：ods_orders" />
          </Form.Item>
          <Form.Item name="targetColumn" label="目标字段">
            <Input placeholder="全表规则可留空" />
          </Form.Item>
          <Form.Item name="threshold" label="阈值" rules={[{ required: true, message: '请输入阈值' }]}>
            <InputNumber min={0} precision={4} style={{ width: '100%' }} placeholder="例如：0.05" />
          </Form.Item>
          <Form.Item name="expression" label="检查表达式">
            <Input.TextArea rows={3} placeholder="范围检查时填写，例如：amount >= 0" />
          </Form.Item>
          <Form.Item name="enabled" label="立即启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Quality;

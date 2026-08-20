import React, { useState } from 'react';
import { history, useModel } from '@umijs/max';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { message } from 'antd';
import { login } from '@/api';

const Login: React.FC = () => {
  const [submitting, setSubmitting] = useState(false);
  const { initialState, setInitialState } = useModel('@@initialState');

  const handleSubmit = async (values: { username: string; password: string }) => {
    setSubmitting(true);
    try {
      const loginResult = await login(values);
      localStorage.setItem('token', loginResult.token);
      message.success('登录成功');
      await setInitialState((s) => ({
        ...s,
        currentUser: loginResult,
      }));
      history.push('/dashboard');
    } catch (e: any) {
      message.error(e?.message || '登录异常，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="rtdwh-login-page">
      <div className="rtdwh-login-panel">
        <div className="rtdwh-login-brand">
          <div className="rtdwh-login-eyebrow">RT-DWH CONTROL CENTER</div>
          <div className="rtdwh-login-title">
            <span className="rtdwh-login-mark">▶</span>
            实时数仓平台
          </div>
          <div className="rtdwh-login-subtitle">
            Flink 2.x + Paimon 实时数据链路管理
          </div>
        </div>

        <LoginForm
          onFinish={handleSubmit}
          submitter={{
            searchConfig: { submitText: '登录' },
            submitButtonProps: { loading: submitting, block: true, size: 'large' },
          }}
        >
          <ProFormText
            name="username"
            fieldProps={{
              size: 'large',
              prefix: <UserOutlined />,
            }}
            placeholder="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          />
          <ProFormText.Password
            name="password"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          />
        </LoginForm>

        <div className="rtdwh-login-hint">
          首次部署请使用 INIT_ADMIN_PASSWORD 初始化管理员账号
        </div>
      </div>
    </div>
  );
};

export default Login;

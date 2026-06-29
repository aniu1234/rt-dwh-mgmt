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
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: '100vh',
        background: 'linear-gradient(135deg, #1a73e8 0%, #0d47a1 100%)',
      }}
    >
      <div
        style={{
          width: 400,
          padding: 40,
          borderRadius: 16,
          background: '#fff',
          boxShadow: '0 8px 40px rgba(0,0,0,0.12)',
        }}
      >
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 28, fontWeight: 700, color: '#1a73e8' }}>
            实时数仓管理平台
          </div>
          <div style={{ fontSize: 14, color: '#888', marginTop: 8 }}>
            RT-DWH Management · Flink 2.x + Paimon
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

        <div style={{ textAlign: 'center', marginTop: 16, color: '#aaa', fontSize: 12 }}>
          默认账号: admin / admin123
        </div>
      </div>
    </div>
  );
};

export default Login;

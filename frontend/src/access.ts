/**
 * access — @umijs/max 权限定义
 * 后端返回 role 为逗号分隔字符串，如 "ADMIN" 或 "ADMIN,DEVELOPER"
 */
export default function access(initialState: { currentUser?: API.CurrentUser } | undefined) {
  const { currentUser } = initialState ?? {};
  const roleStr = currentUser?.role ?? '';
  const roles = roleStr ? roleStr.split(',') : [];

  return {
    canAdmin: !!currentUser && roles.includes('ADMIN'),
    canDeveloper: !!currentUser && (roles.includes('ADMIN') || roles.includes('DEVELOPER')),
    canOperator: !!currentUser && (roles.includes('ADMIN') || roles.includes('VISITOR')),
    canVisitor: !!currentUser && roles.length > 0,
    loggedIn: !!currentUser,
  };
}

/**
 * 测试功能区场景注册表：M0–M8 与 test/Mx-测试用例.md 一一对应。
 */
import m0 from './m0'
import m1 from './m1'
import m2 from './m2'
import m3 from './m3'
import m4 from './m4'
import m5 from './m5'
import m6 from './m6'
import m7 from './m7'
import m8 from './m8'

export const milestones = [m0, m1, m2, m3, m4, m5, m6, m7, m8]

/** 公共测试环境说明（对应 test/M0-测试用例.md 的测试环境表） */
export const envNotes = [
  '后端：cd backend && mvn spring-boot:run -pl data-agent-server（端口 8889）',
  '前端：cd frontend && npm run dev（端口 5173，被占用时自动 +1）',
  '数据库：192.168.2.61 / mini_data_agent（已导入 sql/init.sql + sakila）',
  'Redis：192.168.2.61:6379 db9（Sa-Token 会话）',
  '测试账号：admin / mgr_test / analyst_test / sh_sales / cross_analyst，密码统一 123456',
  '自动用例由页面直接发起真实 HTTP/SSE 请求并判分；手工用例按步骤操作后标记结果；结果仅存本机浏览器（localStorage）'
]

export function getMilestone(m) {
  const found = milestones.find((x) => x.m === Number(m))
  if (!found) throw new Error(`未知里程碑 M${m}`)
  return found
}

/** 侧边栏"测试功能"二级菜单 */
export const testMenus = milestones.map((x) => ({
  path: `/test/m${x.m}`,
  m: x.m,
  label: `M${x.m} · ${x.short}`,
  delivered: x.delivered
}))

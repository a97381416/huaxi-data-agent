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
  '后端：cd backend && mvn install -DskipTests && mvn -pl data-agent-server spring-boot:run（端口 8889）',
  '前端：cd frontend && npm run dev（端口 5173，被占用时自动 +1）',
  '数据库：192.168.2.61 / zrkj-huaxi（华西业务库 public schema，65 张白名单表 + agentx_* 平台表）',
  'Redis：192.168.2.61:6379 db9（与华西主项目共用，key 前缀 huaxi-data-agent:）',
  '测试账号：华西 system_users 账号体系（admin 等，BCrypt 校验）；下方用例中 mgr_test/analyst_test/sh_sales 等为上游历史账号，华西版以 M-H5 验收题集为准',
  '⚠ 本页面题库移植自上游 my-data-agent（M0-M8 验收），业务题已替换为华西等价题；上游专属的账号/数据夹具断言标注后保留，结果仅存本机浏览器（localStorage）'
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

/**
 * M6 测试场景 —— 技能系统 + MCP 外部工具（对应 test/M6-测试用例.md）
 * 已交付：技能 API（/api/skills/**，admin）、SkillsTool、内置三技能、mcp-echarts、Tavily、Vue 技能管理页。
 * 新增依赖：Node.js + mcp-echarts（`npm install -g mcp-echarts && mcp-echarts -t streamable`，:3033/mcp）。
 * A 组 A09/探测可自动判分；zip 上传/启停走技能管理页（E 组），B/C/D 组按对话页手工验证。
 */
export default {
  m: 6,
  delivered: true,
  short: '技能MCP',
  title: 'M6 · 技能系统 + MCP 外部工具',
  theme: 'SkillManager、技能 API（admin）、SkillsTool、内置技能、mcp-echarts、Tavily、Vue 技能管理页',
  groups: [
    {
      key: 'A',
      name: '技能管理 API（/api/skills/** · admin）',
      scenarios: [
        { id: 'M6-A01', prio: 'P0', title: '目录→DB 同步', steps: 'skills 目录放合法技能目录，等待同步或重启', expected: 'agentx_skill 表出现记录：name 唯一、description 来自 frontmatter', code: 'SELECT name, description, enabled FROM agentx_skill;' },
        { id: 'M6-A02', prio: 'P1', title: '3 分钟定时同步', steps: '改动目录内容后等待', expected: '定时任务刷新 DB（不重启生效）' },
        { id: 'M6-A03', prio: 'P0', title: 'zip 上传', steps: 'POST /api/skills/upload 传合法 zip', expected: '解压落目录+DB 入库；SKILL.md 3 层内可定位；frontmatter 提取 name/description', code: 'curl -X POST -H "satoken: $TOKEN" -F "file=@my-skill.zip" http://localhost:8889/api/skills/upload' },
        { id: 'M6-A04', prio: 'P0', title: 'Zip Slip 防护', steps: '构造含 ../../evil.zip 路径的 zip 上传', expected: '拒绝，不写出 skills 目录外' },
        { id: 'M6-A05', prio: 'P0', title: '路径穿越校验', steps: 'name 含 ../ 或 : 等', expected: 'validateSkillName 拒绝' },
        { id: 'M6-A06', prio: 'P1', title: '覆盖上传保状态', steps: '上传同名 zip', expected: '覆盖更新，enabled 状态保留；新技能默认 enabled=1' },
        { id: 'M6-A07', prio: 'P0', title: '启停', steps: 'PUT /api/skills/{name}/toggle', expected: '状态翻转并影响 Agent 可见性（见 B 组）', code: 'curl -X PUT -H "satoken: $TOKEN" http://localhost:8889/api/skills/data-analysis/toggle' },
        { id: 'M6-A08', prio: 'P0', title: '删除', steps: 'DELETE /api/skills/{name}', expected: '目录+DB 删除；孤儿记录被同步清理' },
        {
          id: 'M6-A09',
          prio: 'P0',
          title: '非 admin 拒收',
          steps: '普通账号调上传/删除',
          expected: '403',
          auto: {
            steps: [
              { method: 'POST', url: '/api/skills/upload', headers: { satoken: '{{token:sh_sales}}' }, expect: [{ kind: 'status', value: 403 }] }
            ]
          }
        },
        {
          id: 'M6-A-PROBE',
          prio: 'P0',
          title: '技能列表接口探测',
          steps: 'GET /api/skills（admin）',
          expected: '返回内置三技能（data-analysis / domain-name-brainstormer / skill-creator），enabled 状态正确',
          auto: {
            steps: [
              { method: 'GET', url: '/api/skills', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          },
          note: '上线后端 SkillController（GET /api/skills）已交付，本用例真实判分'
        }
      ]
    },
    {
      key: 'B',
      name: 'SkillsTool（渐进式披露）',
      scenarios: [
        { id: 'M6-B01', prio: 'P0', title: 'description 常驻', steps: '查看任意请求的 tools 定义（日志/trace）', expected: 'SkillsTool 单工具，name+description（frontmatter 渲染）常驻；正文不进上下文' },
        { id: 'M6-B02', prio: 'P0', title: '按需加载执行', steps: '提问匹配某技能 description', expected: 'Agent 先调 SkillsTool(name) 加载 SKILL.md 并严格按其 SOP 执行' },
        { id: 'M6-B03', prio: 'P0', title: '每请求重建', steps: 'DB 停用技能后立即提问', expected: '本次请求工具列表已不含该技能（getEnabledSkillDirs 实时查）' },
        { id: 'M6-B04', prio: 'P1', title: '内置技能齐备', steps: '逐个触发', expected: 'data-analysis（M2 已验）、domain-name-brainstormer、skill-creator 均可加载执行' },
        { id: 'M6-B05', prio: 'P1', title: 'Skills 优先原则', steps: '提问同时可走技能/可走普通工具', expected: '走技能（SystemPrompt 强制）' }
      ]
    },
    {
      key: 'C',
      name: 'mcp-echarts 图表',
      scenarios: [
        { id: 'M6-C01', prio: 'P0', title: '图表生成', steps: '问"按月租金收入画柱状图"', expected: '走 MCP 调用 → 生成图片上传 MinIO → 报告含可访问的图片 URL（浏览器打开可见）', copy: '按月租金收入画一个柱状图' },
        { id: 'M6-C02', prio: 'P1', title: 'streamable 模式', steps: '抓包/日志确认', expected: '以 streamable 模式通信（防 base64 占上下文，响应不进对话上下文）' },
        { id: 'M6-C03', prio: 'P1', title: 'echarts 服务宕机', steps: '停 :3033 再要求画图', expected: '优雅报错，Agent 说明图表服务不可用，正文回答不受阻', copy: '按月租金收入画一个柱状图' }
      ]
    },
    {
      key: 'D',
      name: 'Tavily 联网',
      scenarios: [
        { id: 'M6-D01', prio: 'P0', title: 'online=true', steps: '前端开联网开关问实时信息', expected: '注入 Tavily 工具并被调用，答案含实时来源', copy: '今天有什么科技新闻？' },
        { id: 'M6-D02', prio: 'P0', title: 'online=false', steps: '关闭开关同问', expected: '工具列表无 Tavily；Agent 声明无法联网', copy: '今天有什么科技新闻？' },
        { id: 'M6-D03', prio: 'P1', title: '未配 key 降级', steps: '清空 tavily.api-key 启动', expected: '服务正常起，开关开时不崩（优雅降级）' }
      ]
    },
    {
      key: 'E',
      name: 'Vue 技能管理页（浏览器）',
      scenarios: [
        { id: 'M6-E01', prio: 'P0', title: '列表', steps: '打开技能管理页', expected: '名称/描述/启停状态/来源正确' },
        { id: 'M6-E02', prio: 'P0', title: 'zip 上传', steps: '页面上传一个技能 zip', expected: '成功入库并出现在对话能力中（B02 联动）' },
        { id: 'M6-E03', prio: 'P0', title: '启停/删除', steps: '页面停用→提问不再走该技能；删除后列表消失', expected: '状态即时生效' }
      ]
    }
  ]
}

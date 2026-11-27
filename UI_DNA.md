# UI_DNA · 前端视觉与布局基线

> 基于 `frontend/src` 真实代码提炼，作为后续所有新界面与组件开发的统一视觉基线。

## 1. 技术底座与样式写法
- **技术栈**：Vue `3.5.13` + Vue Router `4.5` + Pinia `2.3` + Element Plus `2.9.1` + `@element-plus/icons-vue` `2.3.1`
- **CSS 方案**：Tailwind CSS `4.3.3`（`@tailwindcss/vite`，无 `tailwind.config.*`）；`frontend/src/assets/main.css`
  仅启用 `tailwindcss/theme.css` 与 `utilities.css`（禁用 preflight，在 `@layer base` 补 `box-sizing: border-box` 与原生 `button` 重置）。
- **辅助库**：图表用 ECharts `5.6.0`；文档渲染用 `markdown-it` + `frontend/src/assets/report-markdown.css`；滚动条用 `.thin-scroll`。
- **写法优先级**：1) **Tailwind v4 工具类**（布局/间距/圆角/色阶首选） > 2) **Element Plus Props** > 3) **`<style scoped>` + `:deep()`**
  （仅用于 Markdown 排版与极少量第三方组件局部微调，禁止新增全局主题覆写文件）。

## 2. 设计变量（Design Tokens）
| 维度 | 真实 Token / Tailwind 类 | 实际值与使用场景 |
| :--- | :--- | :--- |
| **品牌主色** | `blue-600` / `bg-blue-50` / `border-blue-100` | `#2563eb`（激活 Tab 指示条、主链接、用户气泡）；`#eff6ff`（选中高亮、提示卡） |
| **Element 主色** | `el-button type="primary"` | 使用 Element Plus 默认主色，未做全局 CSS 变量覆写 |
| **语义色** | `green-600` / `amber-600` / `orange-500` / `red-500` | 成功状态点 `#10b981`、警告/待处理、高风险与报错 `#ef4444` / `#dc2626` |
| **背景色阶** | `bg-gray-50` (`#f9fafb`) / `bg-white` / `bg-gray-100` | 主框架底色 `#f9fafb`（监控壳 `#f7f8fa`）→ 卡片/顶栏 `#ffffff` → 代码块 `#f3f4f6` |
| **文字色阶** | `text-gray-900` / `700` / `500` / `400` / `300` | 标题 `#111827` → 正文 `#374151` → 标签 `#6b7280` → 辅助/时间 `#9ca3af` → 占位 `#d1d5db` |
| **边框与阴影** | `border-gray-100` / `border-gray-200` / `shadow-sm` | 卡片描边 `#f3f4f6`、控件边框 `#e5e7eb`、内分割线 `border-gray-50`；浮层 `shadow-lg` |
| **圆角体系** | `rounded-xl` / `rounded-lg` / `rounded-2xl` / `rounded-full` | 卡片容器 `12px`、按钮/输入框/内嵌块 `8px`、聊天气泡 `16px`、胶囊/状态点 `9999px` |
| **字体栈** | `main.css` body / `font-mono` | `Inter` + 中文系统无衬线；ID/端点/代码统一 `font-mono` (`ui-monospace, Menlo, Consolas`) |
| **字号阶梯** | `text-[17px]` / `15px` / `text-sm` / `text-xs` / `11px` | 页标题 `17px`、抽屉标题 `15px`、正文/Tab `14px`、表格/表单 `12px`、角标 `11px`、KPI `24px` |
| **间距基准** | `4px` 栅格（`p-4` / `p-3` / `gap-3` / `gap-2`） | 页面留白 `16px`，卡片间距 `12px`，卡片内边距 `12~16px`，筛选控件 `gap-x-4 gap-y-2.5` |

## 3. 页面外壳与容器规范
- **布局壳接入**：业务页均作为 `frontend/src/layouts/MainLayout.vue`（顶栏 `h-14`，居中 Tab + 底部 `h-0.5 bg-blue-600` 指示条）子路由挂载；
  监控子页嵌套 `frontend/src/views/monitor/MonitorShell.vue`（`h-11` 二级页签 + `Ctrl+K` 命令面板）。
- **页面外边距**：`MainLayout` 默认继承 `el-main` 的 `20px` padding；全高自滚动页或铺满列表页需在 `MainLayout.vue` 的 `isFlushRoute`
  中注册去外边距（`padding: 0`），并在页根采用 `flex h-full flex-col p-4`（`16px` 留白），内部卡片包裹（统一 `rounded-xl`）。
- **卡片结构（头/身/脚）**：外框 `rounded-xl border border-gray-100 bg-white shadow-sm overflow-hidden`；
  头 `border-b border-gray-50 px-3.5 py-2.5`、身 `min-h-0 flex-1 overflow-auto`、脚 `border-t border-gray-50 px-3 py-2`。
- **弹窗（`el-dialog`）**：统一带 `destroy-on-close`；常规表单宽 `640px`，轻量确认/单字段 `420px`，预览/大表格 `760px~860px`。
- **抽屉（`el-drawer`）**：统一 `direction="rtl" append-to-body`，宽度取 `420px` / `520px` / `720px`；
  内用 `flex h-full flex-col gap-3`，头部放 `#header`，流式/列表区 `min-h-0 flex-1 overflow-auto`，操作放 `#footer`。

## 4. 高频组件约定
- **按钮选型与对齐**：主操作 `el-button type="primary"`，次操作默认白底按钮，危险操作行内用 `link type="danger"` 或收进「更多」下拉（`divided`）
  并配 `ElMessageBox.confirm({ type: 'warning' })`；工具栏按钮靠右对齐，弹窗 `#footer` 按「辅助 → 取消 → 主确认」右对齐。
- **表单与筛选条**：弹窗内 `el-form` 统一左对齐标签 `label-width="92px"`；
  列表筛选区用 `flex-wrap items-center gap-x-4 gap-y-2.5` 包裹 `<label class="flex items-center gap-1.5 text-xs text-gray-500">`，回车与 `@clear` 触发查询。
- **表格与分页**：高密数据/监控/嵌套表统一 `<el-table size="small">`；常规管理主表用默认尺寸 + `stripe`；操作列 `fixed="right"` 内按钮统一 `link size="small"`，
  间距由父容器 `flex items-center gap-3` 控制。分页统一加 `background` 与 `layout="prev, pager, next, jumper"`。
- **Loading / Empty / Toast**：局部/表格加载用 `v-loading="loading"`；空状态统一 `<el-empty description="..." :image-size="70" />`；
  接口报错已由 `frontend/src/api/request.js` 拦截器统一 `ElMessage.error` 弹出（业务层勿重复弹错），成功反馈用 `ElMessage.success`。

## 5. 参考样板页索引
- **仪表盘 / KPI 6 列网格 / ECharts 双图**：`frontend/src/views/monitor/MonitorOverview.vue`
- **多条件筛选 + 自适应表格 + 分页 + 预览弹窗**：`frontend/src/views/monitor/TraceListView.vue`
- **CRUD 管理台 + 动态表单弹窗 + 详情抽屉**：`frontend/src/views/McpManageView.vue`（同构：`ModelManageView.vue`）
- **左右分栏 + 可折叠列表 + Markdown 报告详情**：`frontend/src/views/monitor/ReportCenterView.vue`
- **流式 AI 抽屉（Header / Stream / Footer）**：`frontend/src/components/DiagnoseDrawer.vue`

## 6. 新界面开发红线（AI 硬约束）
1. **严禁魔法色值与像素**：禁止引入 Token 之外的孤立 hex 色值（如旧 Element 色 `#409eff`、`#606266`）或随意像素，一律使用 Tailwind 色阶与 `4px` 栅格（圆角收敛为 `rounded-xl` / `rounded-lg` / `rounded`）。
2. **严禁外部凑对齐**：禁止用独立 `margin` 或 `position: absolute` 去凑跨区块对齐；需要对齐的区块必须放进同一 `flex` / `grid` 容器并由 `gap-*` 控制间距。
3. **严禁覆写基础组件样式**：优先复用 Element Plus 及 `MdPreviewDialog`、`JsonTree`、`TimelineNav` 等公共组件，禁止写全局 `.el-*` 样式覆写。
4. **严禁重复报错提示**：API 异常已由 `request.js` 拦截器统一弹 `ElMessage.error`，页面 `catch` 块中不得再重复弹错误 Toast。
5. **严禁沿用旧版风格**：新增列表页统一使用 `p-4` + `rounded-xl` 卡片范式与 Tailwind v4 写法，不再复制旧管理页的 `<style scoped>` 裸表模式。

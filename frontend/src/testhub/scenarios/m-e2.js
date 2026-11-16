/**
 * M-E2 测试场景 —— 资料填报（declarant-fill 技能，对应《资料填报技能设计.md》§10 验收场景）。
 * 全对话交互（表单卡/dock 已下线，仅提审确认卡保留）；copy 为可直接粘贴的话术。
 *
 * 依赖：主后端（yudao-server, 9031）可访问、测试账号具有「资料填报」权限
 * （doc:project:file:fill）、测试项目存在进行中阶段（doc_phase 含文件节点与预设类别）、
 * 登录态 token 与主平台同源（文件直传 /admin-api/infra/file/upload）。
 */
export default {
  m: 'E2',
  delivered: true,
  short: '资料填报',
  title: 'M-E2 · 资料填报（declarant-fill 首次填报闭环）',
  theme: '选项目→选阶段→拉清单→确认填报节点→对话填报→暂存→对话配置审核人→确认执行→审批中',
  groups: [
    {
      key: 'A',
      name: '全链路首次填报',
      scenarios: [
        {
          id: 'ME2-01',
          prio: 'P0',
          title: '全链路首次填报（纯对话）',
          steps: '对话：「我要填报项目资料」→ 说项目名 → 说子阶段 → 确认填报节点（说「填报X节点」）→ 对话逐类说明（状态+文件+摘要）→ 暂存 → 「填完了，提交审核」→ 对话说审核人 → 确认执行',
          expected: 'listMyProjects/listPhaseTree 返回文本清单（无卡片）；listFillNodes 不带节点 → 文本节点清单并追问；确认节点后带 nodeNameOrId 重拉 → 聚焦该节点的填报清单；用户对话说明修改 → LLM 转 patch；fillFileEntry 暂存返回文本明细；提审出确认卡（diff+影响面）且紧跟助手正文下方；确认后台账 0→1（审批中）',
          copy: '我要填报项目资料'
        },
        {
          id: 'ME2-11',
          prio: 'P0',
          title: '节点确认门槛',
          steps: 'a) 选阶段拉清单后不说节点；b) 说「填报B节点」；c) 说「填报不存在的节点」',
          expected: 'a) 文本清单 + 追问确认填报节点；b) 返回聚焦 B 节点的清单，换节点跟随切换；c) 工具返回节点清单候选',
          copy: '填报B节点'
        }
      ]
    },
    {
      key: 'B',
      name: '草稿与暂存',
      scenarios: [
        {
          id: 'ME2-02',
          prio: 'P0',
          title: '续填草稿',
          steps: '对同一项目同子阶段再次「我要填报」拉清单',
          expected: 'get-draft 命中，清单标注「续填草稿（批次 DOC-xxx）」；新 patch 合并进现有草稿，不重复建账（docNumber 不变）',
          copy: '继续填上次的资料'
        },
        {
          id: 'ME2-03',
          prio: 'P1',
          title: '暂存半成品（闸2 软校验）',
          steps: '对话把某行状态设为「已取得」但不传文件 → 暂存',
          expected: 'fillFileEntry 暂存成功（半成品合法），但返回 warnings「已取得但无文件」由助手转达；台账仍为草稿',
          copy: '先存一下'
        }
      ]
    },
    {
      key: 'C',
      name: '提审校验（闸3 硬校验）',
      scenarios: [
        {
          id: 'ME2-04',
          prio: 'P0',
          title: '校验-已取得缺文件',
          steps: '某行状态=已取得、无文件 → 直接说「提交审核」',
          expected: 'W2 首轮拒绝出计划，返回缺项清单（「xx」已取得但未上传文件），引导补齐',
          copy: '提交审核'
        },
        {
          id: 'ME2-05',
          prio: 'P0',
          title: '校验-缺摘要（D1 全状态必填）',
          steps: '空缺/不涉及/其他行不填摘要 → 「提交审核」',
          expected: '三种状态缺摘要均被拦截，缺项清单逐行列出（摘要全状态必填）',
          copy: '提交审核'
        },
        {
          id: 'ME2-06',
          prio: 'P0',
          title: '待上传行剔除',
          steps: '保留若干预设行待上传不动 → 其余填完 → 提审 → 看确认计划',
          expected: '预设行 -1 不进本轮提交，执行计划「留草稿」区列出（不影响提交）',
          copy: '提交审核'
        }
      ]
    },
    {
      key: 'D',
      name: '提审配置与令牌',
      scenarios: [
        {
          id: 'ME2-07',
          prio: 'P0',
          title: '审核人配置（纯对话）',
          steps: '对话说审核人（常用推荐/部门+姓名）与提交说明 → 助手调 submitLedgerForReview 首轮',
          expected: 'LLM 从对话解析 assigneeIds+submitRemark；首轮 nextNodes 正确组装（nodeName=部门审核、approvalStrategy=countersign、assigneeIds 为用户ID）',
          copy: '提交审核，审核人张三，提交说明「本阶段资料已齐」'
        },
        {
          id: 'ME2-08',
          prio: 'P0',
          title: '令牌流程',
          steps: 'a) 确认卡等待 >5 分钟后再确认；b) 确认前又在对话里改一行再确认；c) 点 [取消]',
          expected: 'a/b 服务端重出计划（PAYLOAD_MISMATCH/过期→新令牌，不用旧令牌重试）；c) cancelConfirm 作废令牌无副作用',
          copy: '确认执行 ct_xxx'
        }
      ]
    },
    {
      key: 'E',
      name: '边界场景',
      scenarios: [
        {
          id: 'ME2-09',
          prio: 'P0',
          title: '阻断分支',
          steps: 'a) 审批中的台账「我要填报」；b) 已驳回台账「我要填报」；c) 自定义行保持待上传直接提审',
          expected: 'a) 阻断并提示等待审批；b) 引导切 declarant-workflow（重提闭环）；c) 拦截「自定义类别仍为待上传，必须给定明确状态」',
          copy: '我要填报资料'
        },
        {
          id: 'ME2-10',
          prio: 'P1',
          title: '整节点不涉及 / 换文件',
          steps: 'a) 「XX 节点全部不涉及」；b) ⬆ 上传新文件后对话说明「把 XX 类别文件替换为新文件」→ 提审看确认卡 diff',
          expected: 'a) 先列出该节点全部类别清单确认，再批量 patch（禁止自造 selector）；b) patch 覆盖 fileUrl+fileName，确认卡 diff 显示 批复.pdf → 新批复.pdf',
          copy: '批复文件节点全部不涉及'
        }
      ]
    }
  ]
}

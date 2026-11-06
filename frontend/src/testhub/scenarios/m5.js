/**
 * M5 测试场景 —— 文件问答（RAG + 多模态）（对应 test/M5-测试用例.md）
 * 已交付（2026-10-30）：A 组上传接口探测可真实判分；其余走对话页/库/命令手工验证
 * （实测记录见 test/M5-测试用例.md 交付实测记录表）。
 * 新增依赖：MinIO、PostgreSQL+pgvector。
 */
export default {
  m: 5,
  delivered: true,
  short: '文件问答',
  title: 'M5 · 文件问答（RAG + 多模态）',
  theme: 'MinIO+Tika 上传解析、分块+pgvector、analyzeFile 三路路由、多模态、文件-会话双阶段关联',
  groups: [
    {
      key: 'A',
      name: '上传与解析',
      scenarios: [
        { id: 'M5-A01', prio: 'P0', title: '文本类解析', steps: '对话页上传一个多页 PDF', expected: 'status=SUCCESS；extracted_text 有内容（Tika parseToString）' },
        { id: 'M5-A02', prio: 'P0', title: '类型路由-图片', steps: '上传 jpg/png', expected: '只落 MinIO 不识别（延迟到 analyzeFile），元数据完整' },
        { id: 'M5-A03', prio: 'P1', title: '类型路由-其他', steps: '上传 zip/exe', expected: '仅元数据，status=SUCCESS，不报错' },
        { id: 'M5-A04', prio: 'P0', title: '大文件阈值切分', steps: '上传 ≥10000 字符大 PDF', expected: 'embed=1；分块 (500,50) 入 pgvector（重叠 50 字符可验证）' },
        { id: 'M5-A05', prio: 'P1', title: '小文件只存截断文本', steps: '<10000 字符文件', expected: 'embed=0；extracted_text ≤20000 截断，带"细节可 RAG 检索"提示' },
        { id: 'M5-A06', prio: 'P1', title: '单轮文件数上限', steps: '一轮传 4 个文件', expected: '第 4 个被拒（max-count=3）' },
        { id: 'M5-A07', prio: 'P1', title: '大小限制', steps: '>100MB 文件', expected: '拒绝' },
        { id: 'M5-A08', prio: 'P2', title: '表自动建', steps: '首次启动观察 agentx_file', expected: '自动建表（幂等补列补索引）' },
        { id: 'M5-A09', prio: 'P1', title: '预览', steps: 'GET /api/files/{id}/preview', expected: '原始字节正确；图片 MIME 按扩展名映射；缓存 7 天头', code: 'curl -i -H "satoken: $TOKEN" "http://localhost:8889/api/files/<id>/preview" -o preview.bin' },
        { id: 'M5-A10', prio: 'P1', title: '删除', steps: 'DELETE /api/files/{id}', expected: 'MinIO+DB 先后删；MinIO 失败仅 warn 不 500', code: 'curl -X DELETE -H "satoken: $TOKEN" "http://localhost:8889/api/files/<id>"' },
        {
          id: 'M5-A-PROBE',
          prio: 'P0',
          title: '上传接口探测',
          steps: 'POST /api/files/upload（自动生成一个小 txt 上传）',
          expected: '交付后返回文件元数据；当前未交付会显示 blocked',
          auto: {
            steps: [
              {
                method: 'POST',
                url: '/api/files/upload',
                uploadFile: { field: 'file', filename: 'm5-probe.txt', content: 'testhub upload probe' },
                timeoutMs: 30000,
                expect: [{ kind: 'statusIn', value: [200, 400] }]
              }
            ]
          },
          note: '上传接口路径/字段以交付时后端 Controller 为准，如不符请调整本用例定义'
        }
      ]
    },
    {
      key: 'B',
      name: 'RAG 检索（analyzeFile 大文件路径）',
      scenarios: [
        { id: 'M5-B01', prio: 'P0', title: '细节命中', steps: '上传大 PDF 后问"文中关于 XX 的第 N 点是什么"', expected: '走 RAG 四步（压缩→3 变体扩展→过滤检索 topK=5→去重合并），命中正确片段作答' },
        { id: 'M5-B02', prio: 'P1', title: '检索失败降级', steps: '构造任一步失败', expected: '返回空列表降级，不阻断回答' },
        { id: 'M5-B03', prio: 'P0', title: '向量隔离', steps: 'A 文件的提问不命中 B 文件块', expected: 'metadata filter fileid 生效' }
      ]
    },
    {
      key: 'C',
      name: '多模态（图片路径）',
      scenarios: [
        { id: 'M5-C01', prio: 'P0', title: '图片内容问答', steps: '上传含文字/场景的图片，问"图里有什么"', expected: '走 qwen VL 多模态描述（场景/对象/布局/颜色/文字），回答正确', copy: '图里有什么？' },
        { id: 'M5-C02', prio: 'P1', title: '懒缓存回写', steps: '同一图片第二次问', expected: '命中回写的 extracted_text，不再调 VL（日志/计数验证）' },
        { id: 'M5-C03', prio: 'P1', title: 'HttpURLConnection 实现', steps: '代码审查', expected: '多模态走 JDK HttpURLConnection（规避 Netty 大 base64 卡死）' }
      ]
    },
    {
      key: 'D',
      name: '状态路由与文件-会话关联',
      scenarios: [
        { id: 'M5-D01', prio: 'P0', title: '状态≠SUCCESS 提示', steps: '上传中/失败文件调 analyzeFile', expected: '返回状态提示（如"文件处理中"），不执行检索' },
        { id: 'M5-D02', prio: 'P0', title: '本次组指代', steps: '同轮传 2 个文件后说"分析这个"', expected: '触发本次上传整组逐个 analyzeFile', copy: '分析这个' },
        { id: 'M5-D03', prio: 'P0', title: '上一篇指代', steps: '历史传过文件，新轮说"再分析一下上一篇"', expected: '取历史最新一轮文件', copy: '再分析一下上一篇' },
        { id: 'M5-D04', prio: 'P1', title: '闲聊不触发', steps: '上传后闲聊"你好"', expected: '不调 analyzeFile', copy: '你好' },
        { id: 'M5-D05', prio: 'P1', title: '会话文件段', steps: '审查运行时 system prompt', expected: '含本次组+历史按轮分组及触发规则' },
        { id: 'M5-D06', prio: 'P1', title: '双阶段关联', steps: '流结束查库', expected: 'fileIds 已 linkToConversation（流启动）且 linkToSession（Complete/Paused 后）' }
      ]
    },
    {
      key: 'E',
      name: '降级与韧性',
      scenarios: [
        { id: 'M5-E01', prio: 'P0', title: 'pgvector 宕机', steps: '停 PG 再上传大文件', expected: '回退 embed=0，上传主流程不阻断' },
        { id: 'M5-E02', prio: 'P1', title: 'MinIO 宕机', steps: '停 MinIO 上传', expected: '明确报错（存储是主链路，允许失败但不崩服务）' }
      ]
    }
  ]
}

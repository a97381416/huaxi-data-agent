<template>
  <div class="json-tree font-mono text-xs leading-5">
    <JsonNode :value="root" :name="rootKey" :depth="0" :expand-depth="expandDepth" :preview="longTextPreview" />
  </div>
</template>

<script setup>
import { computed, h, defineComponent, ref } from 'vue'

/**
 * JSON 树查看器（T4.3，设计 §6.4 ③）：懒解析 + 默认只展开 2 层，
 * 长字符串折叠为单行预览（点击展开）。
 * 用 defineComponent + render 函数递归，避免自身引用的 setup 递归注册问题。
 */
const props = defineProps({
  /** JSON 原文（自动 parse，失败则按纯文本展示） */
  raw: { type: String, default: '' },
  /** 已解析对象（可选，优先于 raw） */
  data: { type: null, default: undefined },
  rootKey: { type: String, default: 'root' },
  expandDepth: { type: Number, default: 2 },
  /**
   * 长字符串「点击展开」回调（可选）：传入后点击不再行内展开，
   * 改为触发回调（2026-11-19 评审反馈：统一弹窗渲染 MD）。参数 = (全文, 字段名)
   */
  longTextPreview: { type: Function, default: null }
})

const root = computed(() => {
  if (props.data !== undefined) return props.data
  try {
    return JSON.parse(props.raw)
  } catch {
    return props.raw
  }
})

const CHAR_LIMIT = 120 // 字符串单行预览上限
const PREVIEW_LIMIT = 20 // 对象/数组折叠预览条数

/** 递归节点组件（render 写法便于自引用） */
const JsonNode = defineComponent({
  name: 'JsonNode',
  props: {
    value: { required: true },
    name: { type: String, default: '' },
    depth: { type: Number, default: 0 },
    expandDepth: { type: Number, default: 2 },
    preview: { type: Function, default: null }
  },
  setup(nodeProps) {
    return () => renderValue(nodeProps.value, nodeProps.name, nodeProps.depth, nodeProps.expandDepth, nodeProps.preview)
  }
})

function kindOf(v) {
  if (v === null) return 'null'
  if (Array.isArray(v)) return 'array'
  return typeof v
}

function renderValue(value, name, depth, expandDepth, preview) {
  const kind = kindOf(value)
  const keyLabel = name
    ? h('span', { class: 'text-purple-600' }, [
        name,
        h('span', { class: 'text-gray-400' }, ': ')
      ])
    : null

  if (kind === 'object' || kind === 'array') {
    const entries = kind === 'array'
      ? value.map((v, i) => [String(i), v])
      : Object.entries(value)
    const expandedDefault = depth < expandDepth
    return h(JsonBranch, {
      keyLabel,
      entries,
      isBracket: kind === 'array',
      count: entries.length,
      expandedDefault,
      depth,
      expandDepth,
      preview
    })
  }
  if (kind === 'string') {
    const long = value.length > CHAR_LIMIT
    if (!long) {
      return h('div', { class: 'pl-2' }, [
        keyLabel,
        h('span', { class: 'text-green-700' }, JSON.stringify(value))
      ])
    }
    return h(JsonLongString, { keyLabel, text: value, name, preview })
  }
  const cls = kind === 'number' ? 'text-blue-700'
    : kind === 'boolean' ? 'text-orange-600'
    : 'text-gray-400'
  const text = kind === 'null' ? 'null' : kind === 'undefined' ? 'undefined' : String(value)
  return h('div', { class: 'pl-2' }, [keyLabel, h('span', { class: cls }, text)])
}

/** 可折叠分支（对象/数组） */
const JsonBranch = defineComponent({
  name: 'JsonBranch',
  props: {
    keyLabel: { default: null },
    entries: { type: Array, required: true },
    isBracket: Boolean,
    count: { type: Number, default: 0 },
    expandedDefault: Boolean,
    depth: { type: Number, default: 0 },
    expandDepth: { type: Number, default: 2 },
    preview: { type: Function, default: null }
  },
  setup(branchProps) {
    // 必须用 ref：普通变量无响应式，toggle 改值不会触发重渲染（点击展开会失效）
    const expanded = ref(branchProps.expandedDefault)
    return () => {
      const open = String.fromCharCode(branchProps.isBracket ? 91 : 123)
      const close = String.fromCharCode(branchProps.isBracket ? 93 : 125)
      const toggle = h(
        'span',
        {
          class: 'cursor-pointer select-none text-gray-400 hover:text-blue-500',
          onClick: () => (expanded.value = !expanded.value)
        },
        expanded.value ? '▾ ' : '▸ '
      )
      if (!expanded.value) {
        // 折叠态：预览前 N 条
        const preview = branchProps.entries.slice(0, PREVIEW_LIMIT).map(([k, v], i) => {
          const kind = kindOf(v)
          const label = kind === 'object' || kind === 'array'
            ? (kind === 'array' ? `[${v.length}]` : `{${Object.keys(v).length}}`)
            : kind === 'string' ? truncated(String(v), 30)
            : String(v)
          const color = kind === 'string' ? 'text-green-700'
            : kind === 'object' || kind === 'array' ? 'text-gray-500' : 'text-blue-700'
          return h('span', { key: i, class: color }, [
            branchProps.isBracket ? '' : k + ': ',
            label,
            i < Math.min(branchProps.entries.length, PREVIEW_LIMIT) - 1 ? ', ' : ''
          ])
        })
        return h('div', { class: 'pl-2' }, [
          branchProps.keyLabel,
          toggle,
          h('span', { class: 'text-gray-500' }, open),
          ...preview,
          branchProps.entries.length > PREVIEW_LIMIT
            ? h('span', { class: 'text-gray-400' }, ` … 共 ${branchProps.entries.length} 条`)
            : null,
          h('span', { class: 'text-gray-500' }, close)
        ])
      }
      return h('div', { class: 'pl-2' }, [
        branchProps.keyLabel,
        toggle,
        h('span', { class: 'text-gray-500' }, open),
        h(
          'div',
          { class: 'pl-4 border-l border-dashed border-gray-200 ml-1' },
          branchProps.entries.map(([k, v]) => renderValue(v, k, branchProps.depth + 1, branchProps.expandDepth, branchProps.preview))
        ),
        h('span', { class: 'text-gray-500' }, close)
      ])
    }
  }
})

/** 长字符串：单行预览 + 点击展开全文（可再收起）；传入 preview 回调时点击改触发弹窗 */
const JsonLongString = defineComponent({
  name: 'JsonLongString',
  props: {
    keyLabel: { default: null },
    text: { type: String, required: true },
    name: { type: String, default: '' },
    preview: { type: Function, default: null }
  },
  setup(longProps) {
    const expanded = ref(false)
    const onClick = () => {
      if (longProps.preview) {
        longProps.preview(longProps.text, longProps.name)
        return
      }
      expanded.value = !expanded.value
    }
    return () => {
      const toggle = h(
        'span',
        {
          class: 'cursor-pointer select-none text-gray-400 hover:text-blue-500',
          onClick
        },
        expanded.value ? '▾ ' : '▸ '
      )
      return h('div', { class: 'pl-2' }, [
        longProps.keyLabel,
        toggle,
        expanded.value
          ? h('span', { class: 'whitespace-pre-wrap break-all text-green-700' }, JSON.stringify(longProps.text))
          : h('span', { class: 'text-green-700' }, [
              JSON.stringify(truncated(longProps.text, CHAR_LIMIT)),
              h('span', { class: 'ml-1 text-gray-400' }, `（${longProps.text.length} 字符，点击展开）`)
            ])
      ])
    }
  }
})

function truncated(text, max) {
  const line = text.replaceAll('\n', '␤')
  return line.length > max ? line.slice(0, max) + '…' : line
}
</script>

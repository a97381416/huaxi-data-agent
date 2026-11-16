<template>
  <!-- 对话内时间线导航：纯浮层（absolute，不占布局、无背景列），挂在聊天区 section 左缘。
       条目 = 一组粗短线（示意该轮内容），hover 浮出提问预览气泡，点击跳转到该轮。
       容器 pointer-events-none：空白处不挡聊天区的点击/滚动，只有条目本身可交互。 -->
  <div ref="railRef" class="pointer-events-none absolute bottom-28 left-0 top-1.5 z-20">
    <div class="thin-scroll h-full overflow-y-auto px-1 pb-2">
      <div class="flex flex-col gap-1">
        <div
          v-for="r in rounds"
          :key="r.index"
          class="pointer-events-auto flex cursor-pointer items-center rounded-md px-1.5 py-0.5 transition-colors duration-150 hover:bg-gray-100"
          :class="r.index === activeIndex ? 'bg-blue-50' : ''"
          :title="r.preview"
          @mouseenter="showHover($event.currentTarget, r)"
          @mouseleave="hover = null"
          @click="emit('jump', r.index)"
        >
          <!-- 一个提问对应一条横杠 -->
          <i class="h-[2px] w-[9px] rounded-full bg-gray-300" :class="r.index === activeIndex ? '!bg-blue-400' : ''"></i>
        </div>
      </div>
    </div>

    <!-- 悬浮预览气泡：盖在聊天区上，随悬停项定位（不占布局空间）；需固定宽度——收缩适配会被挤成竖排 -->
    <div
      v-if="hover"
      class="pointer-events-none absolute z-30 w-[280px] rounded-lg border border-gray-200 bg-white px-3 py-2 shadow-lg"
      :style="{ top: hover.top + 'px', left: hover.left + 'px' }"
    >
      <p class="line-clamp-4 break-all text-xs leading-5 text-gray-600">{{ hover.preview }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  /** 导航项：[{ index: 消息锚点下标, ts: 提问时间毫秒, preview: 提问预览文本 }] */
  rounds: { type: Array, default: () => [] },
  /** 当前滚动位置所在轮次的消息下标（-1 = 无命中不高亮） */
  activeIndex: { type: Number, default: -1 },
})
const emit = defineEmits(['jump'])

const railRef = ref(null)
/** 悬浮气泡状态：{ top, preview }（null = 隐藏） */
const hover = ref(null)

/** 悬停某项：气泡对齐该项在浮层内的位置（贴近视口上下边缘时收拢），横移到浮层右侧 6px，内容为提问预览 */
function showHover(el, r) {
  const rail = railRef.value
  if (!rail) return
  const railRect = rail.getBoundingClientRect()
  const itemTop = el.getBoundingClientRect().top - railRect.top
  const top = Math.min(Math.max(itemTop, 4), Math.max(railRect.height - 110, 4))
  const left = railRect.width + 6
  hover.value = { top, left, preview: r.preview || '（附件/文件消息）' }
}
</script>

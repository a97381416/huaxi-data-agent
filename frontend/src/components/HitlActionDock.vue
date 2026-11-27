<template>
  <div v-if="visibleCards.length" class="mx-auto mb-2.5 w-full max-w-4xl space-y-2">
    <component
      v-for="card in visibleCards"
      :key="card.decisionId"
      :is="cardComponent(card.type)"
      :card="card"
      :highlighted="focusDecisionId === card.decisionId"
      @decided="(resp) => $emit('decided', resp)"
      @expired="(id) => $emit('expired', id)"
      @revoked="(gid) => $emit('revoked', gid)"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import HitlPermissionCard from '@/components/HitlPermissionCard.vue'
import HitlQuestionCard from '@/components/HitlQuestionCard.vue'
import HitlVerifyCard from '@/components/HitlVerifyCard.vue'
import HitlPlanCard from '@/components/HitlPlanCard.vue'

function cardComponent(type) {
  if (type === 'QUESTION') return HitlQuestionCard
  if (type === 'VERIFY_OUTCOME') return HitlVerifyCard
  if (type === 'PLAN_CONFIRM') return HitlPlanCard
  return HitlPermissionCard
}

const props = defineProps({
  decisions: { type: Array, default: () => [] },
  focusDecisionId: { type: String, default: '' }
})

defineEmits(['decided', 'expired', 'revoked'])

/** 单焦点槽位：仅展示 1 张活跃待处理（PENDING）卡片，优先显示由抽屉跳转聚焦的决策单；已处理卡片不再常驻输入框上方 */
const visibleCards = computed(() => {
  const list = props.decisions || []
  const pending = list.filter((d) => d.status === 'PENDING')
  if (!pending.length) return []
  if (props.focusDecisionId) {
    const focused = pending.find((d) => d.decisionId === props.focusDecisionId)
    if (focused) return [focused]
  }
  return [pending[0]]
})
</script>

<template>
  <div class="home">
    <el-card shadow="never" class="home-card">
      <template #header>
        <span>当前登录用户（数据来自 GET /auth/me）</span>
      </template>

      <el-skeleton :loading="!userStore.user" :rows="4" animated>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="用户名">{{ u.username }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ u.nickname }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="u.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
              {{ u.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="数据范围">
            <el-tag type="warning" size="small">{{ u.dataScope }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="角色" :span="2">
            <el-tag v-for="r in u.roles || []" :key="r.id" class="gap-tag" size="small">
              {{ r.name }}（{{ r.code }}）
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="部门" :span="2">
            <span v-for="d in u.depts || []" :key="d.id" class="dept-path gap-tag">
              {{ (d.path || []).join(' / ') }}
            </span>
          </el-descriptions-item>
          <template v-if="u.profile">
            <el-descriptions-item label="真实姓名">{{ u.profile.realName }}</el-descriptions-item>
            <el-descriptions-item label="年龄">{{ u.profile.age }}</el-descriptions-item>
            <el-descriptions-item label="学历">{{ u.profile.education }}</el-descriptions-item>
            <el-descriptions-item label="家庭住址">{{ u.profile.homeAddress }}</el-descriptions-item>
          </template>
        </el-descriptions>
      </el-skeleton>
    </el-card>

    <el-card shadow="never" class="home-card">
      <template #header>
        <span>里程碑进度</span>
      </template>
      <el-steps :active="2" align-center finish-status="success">
        <el-step title="M0 骨架底座" description="登录/库表/通用设施" />
        <el-step title="M1 Agent 内核" description="ReAct/流式事件" />
        <el-step title="M2 NL2SQL" description="自然语言→报告" />
        <el-step title="M3-M8" description="体验/安全/文件/技能/评测" />
      </el-steps>
      <el-alert
        class="home-alert"
        type="success"
        :closable="false"
        title="M0 + M1 已完成：骨架底座 + 自研 Agent 内核"
        description="内核演示页已开放（左侧菜单）；智能对话（M2）、用户管理（M4）、技能管理（M6）随里程碑启用。详见项目 README 里程碑进度表。"
      />
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const u = computed(() => userStore.user || {})
</script>

<style scoped>
.home {
  max-width: 960px;
  margin: 0 auto;
}

.home-card {
  margin-bottom: 16px;
}

.home-alert {
  margin-top: 20px;
}

.gap-tag {
  margin-right: 8px;
}

.dept-path {
  display: inline-block;
  padding: 2px 8px;
  background: #f4f4f5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
</style>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <!-- 品牌 -->
      <div class="login-brand">
        <div class="login-logo"><el-icon :size="30"><Monitor /></el-icon></div>
        <h1 class="login-title">数据分析智能体</h1>
        <p class="login-subtitle">my-data-agent · 登录</p>
      </div>

      <el-form class="login-form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input
            v-model="username"
            size="large"
            autocomplete="username"
            placeholder="请输入用户名"
            :disabled="loading"
            ref="usernameInput"
          >
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-input
            v-model="password"
            size="large"
            :type="showPassword ? 'text' : 'password'"
            :autocomplete="showPassword ? 'off' : 'current-password'"
            placeholder="请输入密码"
            :disabled="loading"
            @keyup.enter="onSubmit"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
            <template #suffix>
              <el-icon class="pwd-toggle" @click="showPassword = !showPassword" :title="showPassword ? '隐藏密码' : '显示密码'">
                <Hide v-if="showPassword" />
                <View v-else />
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <div v-if="errorMsg" class="login-error">
          <el-icon><CircleCloseFilled /></el-icon>
          <span>{{ errorMsg }}</span>
        </div>

        <el-button
          type="primary"
          size="large"
          class="login-submit"
          :loading="loading"
          native-type="submit"
        >
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <div class="login-tip">默认账号 admin / 123456</div>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCloseFilled, Hide, Lock, Monitor, User, View } from '@element-plus/icons-vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const errorMsg = ref('')
const usernameInput = ref(null)

// 已登录直接跳主页（对齐原版 login.js 行为）
onMounted(() => {
  if (userStore.token) {
    router.replace('/')
    return
  }
  nextTick(() => usernameInput.value?.focus())
})

async function onSubmit() {
  errorMsg.value = ''
  if (!username.value.trim() || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    const vo = await login({ username: username.value.trim(), password: password.value })
    userStore.setLogin(vo)
    ElMessage.success('登录成功')
    router.replace('/')
  } catch (e) {
    errorMsg.value = e?.message || '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 380px;
  border-radius: 12px;
  padding: 12px 8px;
}

.login-brand {
  text-align: center;
  margin-bottom: 24px;
}

.login-logo {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, #409eff, #7b5cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: #909399;
}

.pwd-toggle {
  cursor: pointer;
}

.login-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 13px;
}

.login-submit {
  width: 100%;
  letter-spacing: 4px;
}

.login-tip {
  margin-top: 14px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
</style>

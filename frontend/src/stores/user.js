import { defineStore } from 'pinia'

const TOKEN_KEY = 'da-token'
const USER_KEY = 'da-user'

function readCachedUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

/**
 * 当前登录用户状态（token + 用户信息，localStorage 持久化）。
 * 字段结构与后端 LoginUserVO 一致：roles / depts(path) / dataScope / profile。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readCachedUser()
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    // 华西 yudao 超管角色 code 为 super_admin（上游 my-data-agent 为 admin，两者都认）
    isAdmin: (state) =>
      Array.isArray(state.user?.roles) &&
      state.user.roles.some((r) => ['admin', 'super_admin'].includes(r?.code))
  },

  actions: {
    setLogin(loginVO) {
      this.token = loginVO.token || ''
      this.user = loginVO
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(USER_KEY, JSON.stringify(loginVO || {}))
    },

    setUser(user) {
      this.user = user
      localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
    },

    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})

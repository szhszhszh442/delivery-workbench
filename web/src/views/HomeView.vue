<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getCurrentUser, login, logout, type CurrentUser } from '../api/auth'
import DirectoryView from './DirectoryView.vue'

const user = ref<CurrentUser>()
const loginName = ref('')
const password = ref('')
const error = ref('')
const restoring = ref(true)
const submitting = ref(false)

function describeError(failure: unknown): string {
  return failure instanceof ApiError ? failure.message : '暂时无法连接后端，请稍后重试'
}

async function restoreSession() {
  try {
    user.value = await getCurrentUser()
  } catch (failure) {
    if (!(failure instanceof ApiError && failure.status === 401)) {
      error.value = describeError(failure)
    }
  } finally {
    restoring.value = false
  }
}

async function submitLogin() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    user.value = await login(loginName.value, password.value)
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    password.value = ''
    submitting.value = false
  }
}

async function submitLogout() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await logout()
    user.value = undefined
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      user.value = undefined
    } else {
      error.value = describeError(failure)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(restoreSession)
</script>

<template>
  <DirectoryView
    v-if="user"
    :user="user"
    :signing-out="submitting"
    :logout-error="error"
    @logout="submitLogout"
    @session-expired="user = undefined"
  />
  <main v-else>
    <section aria-labelledby="title" class="account-panel">
      <h1 id="title">项目交付协作平台</h1>

      <p v-if="restoring" role="status">正在检查登录状态…</p>
      <form v-else @submit.prevent="submitLogin">
        <label>
          账号
          <input v-model="loginName" name="username" autocomplete="username" maxlength="64" required>
        </label>
        <label>
          密码
          <input
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            maxlength="72"
            required
          >
        </label>
        <button type="submit" :disabled="submitting">
          {{ submitting ? '正在登录…' : '登录' }}
        </button>
      </form>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </section>
  </main>
</template>

<style scoped>
main {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 1.5rem;
  box-sizing: border-box;
  background: #f3f6fa;
  color: #142438;
  font-family: 'Inter', system-ui, sans-serif;
}

.account-panel {
  width: min(100%, 24rem);
  padding: clamp(1.5rem, 5vw, 2.5rem);
  box-sizing: border-box;
  border: 1px solid #dce3ec;
  border-radius: 1rem;
  background: #fff;
}

h1 {
  margin: 0 0 1.75rem;
  font-size: 1.45rem;
  letter-spacing: -0.02em;
}

form,
label {
  display: grid;
  gap: 0.45rem;
}

form {
  gap: 1rem;
}

label {
  font-size: 0.9rem;
  font-weight: 600;
}

input,
button {
  box-sizing: border-box;
  min-height: 2.65rem;
  padding: 0.55rem 0.8rem;
  border: 1px solid #becbdd;
  border-radius: 0.45rem;
  font: inherit;
}

button {
  margin-top: 0.35rem;
  border-color: #173d72;
  background: #173d72;
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}

button:disabled {
  opacity: 0.65;
  cursor: wait;
}

input:focus-visible,
button:focus-visible {
  outline: 2px solid #3464ab;
  outline-offset: 2px;
}

.error {
  color: #a52832;
  font-size: 0.9rem;
}
</style>

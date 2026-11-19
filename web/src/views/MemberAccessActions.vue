<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError, request } from '../api/auth'
import { createCommandSender } from '../api/commands'
import type { ProjectMember, ProjectRole, WorkspaceMember, WorkspaceRole } from '../api/directory'
import { taskStatusNames, type TaskStatus } from '../api/tasks'

interface Responsibilities {
  lastEffectiveOwner: boolean
  items: { taskId: string; title: string; status: TaskStatus; assignee: boolean; reviewer: boolean }[]
}

const props = defineProps<{
  scope: 'project' | 'workspace'
  scopeId: string
  member: ProjectMember | WorkspaceMember
  manager: boolean
  self: boolean
}>()
const emit = defineEmits<{ changed: []; sessionExpired: []; focusTask: [id: string] }>()
const roles = computed(() => props.scope === 'project'
  ? [{ id: 'OWNER', label: '项目负责人' }, { id: 'CONTRIBUTOR', label: '执行成员' }, { id: 'OBSERVER', label: '观察成员' }]
  : [{ id: 'ADMIN', label: '空间管理员' }, { id: 'MEMBER', label: '空间成员' }])
const memberPath = computed(() => `/${props.scope}s/${props.scopeId}/members/${props.member.id}`)
const canChangeRole = computed(() => props.manager && props.member.status === 'ACTIVE')
const canRemove = computed(() => (props.manager || props.self) && props.member.status === 'ACTIVE')
const canViewDuties = computed(() => props.scope === 'project' && (props.manager || props.self))
const open = ref(false)
const busy = ref(false)
const error = ref('')
const stale = ref(false)
const affectedProjects = ref<string[]>([])
const duties = ref<Responsibilities>()
const role = ref<ProjectRole | WorkspaceRole>(props.member.role)
const roleReason = ref('')
const removalReason = ref('')
const mode = ref<'REQUIRE_CLEAR' | 'REVOKE_NOW'>('REQUIRE_CLEAR')
const command = createCommandSender()

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  affectedProjects.value = []
  try {
    await action()
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      emit('sessionExpired')
    } else if (failure instanceof ApiError) {
      stale.value = failure.code === 'VERSION_CONFLICT'
      const projects = failure.details?.projectIds
      affectedProjects.value = Array.isArray(projects) ? projects.filter((id) => typeof id === 'string') : []
      error.value = failure.message
    } else {
      error.value = '暂时无法连接后端，可以用相同输入重试'
    }
  } finally {
    busy.value = false
  }
}

function inspect() {
  open.value = true
  role.value = props.member.role
  if (canViewDuties.value) {
    void run(async () => { duties.value = await request<Responsibilities>(memberPath.value + '/responsibilities') })
  }
}

function saveRole() {
  return run(async () => {
    await command(memberPath.value, {
      expectedVersion: props.member.version,
      role: role.value,
      ...(props.scope === 'project' ? { reason: roleReason.value.trim() } : {}),
    }, 'PATCH')
    emit('changed')
  })
}

function remove() {
  return run(async () => {
    await command(memberPath.value + '/remove', {
      expectedVersion: props.member.version,
      reason: removalReason.value.trim(),
      mode: mode.value,
    })
    emit('changed')
  })
}
</script>

<template>
  <div v-if="canChangeRole || canRemove || canViewDuties" class="access">
    <button v-if="!open" type="button" @click="inspect">{{ manager ? '管理成员' : '查看职责或离开' }}</button>
    <section v-else>
      <button type="button" :disabled="busy" @click="open = false">收起</button>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="affectedProjects.length">相关项目 ID {{ affectedProjects.join('、') }}</p>
      <button v-if="stale" type="button" :disabled="busy" @click="emit('changed')">刷新成员资格</button>
      <div v-if="duties">
        <h4>未完成职责</h4>
        <p v-if="!duties.items.length">没有未完成职责。</p>
        <ul v-else>
          <li v-for="task in duties.items" :key="task.taskId">
            {{ task.title }} · {{ taskStatusNames[task.status] }}
            <span v-if="task.assignee"> · 执行</span>
            <span v-if="task.reviewer"> · 确认</span>
            <button type="button" @click="emit('focusTask', task.taskId)">打开任务</button>
          </li>
        </ul>
        <p v-if="duties.lastEffectiveOwner">这是当前唯一有效的项目负责人。</p>
      </div>

      <form v-if="canChangeRole" @submit.prevent="saveRole">
        <label>新角色
          <select v-model="role">
            <option v-for="option in roles" :key="option.id" :value="option.id">{{ option.label }}</option>
          </select>
        </label>
        <label v-if="scope === 'project'">角色变更原因
          <input v-model="roleReason" required maxlength="60000">
        </label>
        <p v-if="role === 'OBSERVER' && member.role !== 'OBSERVER' && duties?.items.length">
          降为观察成员后仍可查看项目。现有执行和确认职责会失效，需要负责人重新安排。
        </p>
        <button :disabled="busy || stale || role === member.role">保存角色</button>
      </form>

      <form v-if="canRemove" @submit.prevent="remove">
        <label>撤权方式
          <select v-model="mode">
            <option value="REQUIRE_CLEAR">移交未完成职责后移除</option>
            <option value="REVOKE_NOW">立即撤权，保留职责待恢复</option>
          </select>
        </label>
        <label>移除或离开的原因 <input v-model="removalReason" required maxlength="60000"></label>
        <button :disabled="busy || stale">{{ self ? '离开' : '移除成员' }}</button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.access { margin-block: 0.35rem 0.8rem; }
section { padding: 0.65rem 0 0.2rem 0.75rem; border-left: 2px solid #dce3ec; }
form { display: grid; gap: 0.45rem; margin: 0.65rem 0; width: min(100%, 30rem); }
label { display: grid; gap: 0.35rem; }
button, select, input { font: inherit; padding: 0.32rem 0.55rem; }
button { justify-self: start; }
li button { margin-left: 0.55rem; }
.error { color: #a52832; }
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createCommandSender } from '../api/commands'
import TaskPanel from './TaskPanel.vue'
import DocumentPanel from './DocumentPanel.vue'
import MemberAccessActions from './MemberAccessActions.vue'
import { ApiError, request, type CurrentUser } from '../api/auth'
import {
  list,
  type Workspace,
  type Project,
  type WorkspaceMember,
  type ProjectMember,
  type WorkspaceRole,
  type ProjectRole,
} from '../api/directory'

defineProps<{ user: CurrentUser; signingOut: boolean; logoutError: string }>()
const emit = defineEmits<{ logout: []; sessionExpired: [] }>()

const workspaces = ref<Workspace[]>([])
const workspace = ref<Workspace>()
const projects = ref<Project[]>([])
const project = ref<Project>()
const workspaceMembers = ref<WorkspaceMember[]>([])
const projectMembers = ref<ProjectMember[]>([])
const requestedTask = ref<{ id: string }>()

const workspaceName = ref('')
const projectName = ref('')
const projectGoal = ref('')
const memberLogin = ref('')
const workspaceRole = ref<WorkspaceRole>('MEMBER')
const projectMemberId = ref('')
const projectRole = ref<ProjectRole>('CONTRIBUTOR')
const busy = ref(false)
const error = ref('')
const command = createCommandSender()

const roleNames = {
  ADMIN: '空间管理员',
  MEMBER: '空间成员',
  OWNER: '项目负责人',
  CONTRIBUTOR: '执行成员',
  OBSERVER: '观察成员',
}
const candidates = computed(() =>
  workspaceMembers.value.filter((member) =>
    member.status === 'ACTIVE' && !projectMembers.value.some(
      (projectMember) => projectMember.workspaceMemberId === member.id && projectMember.effective,
    ),
  ),
)

async function run(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await operation()
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      emit('sessionExpired')
      return
    }
    error.value = failure instanceof ApiError ? failure.message : '暂时无法连接后端，可以重试原操作'
  } finally {
    busy.value = false
  }
}

async function refreshWorkspaces(preferredId?: string) {
  workspaces.value = await list<Workspace>('/workspaces')
  const next = workspaces.value.find((item) => item.id === preferredId) ?? workspaces.value[0]
  await selectWorkspace(next)
}

async function selectWorkspace(item?: Workspace) {
  workspace.value = item
  projects.value = []
  project.value = undefined
  workspaceMembers.value = []
  projectMembers.value = []
  requestedTask.value = undefined
  projectMemberId.value = ''
  if (!item) return
  const [availableProjects, members] = await Promise.all([
    list<Project>(`/workspaces/${item.id}/projects`),
    list<WorkspaceMember>(`/workspaces/${item.id}/members`),
  ])
  projects.value = availableProjects
  workspaceMembers.value = members
}

async function selectProject(projectId: string) {
  project.value = undefined
  projectMembers.value = []
  requestedTask.value = undefined
  projectMemberId.value = ''
  const [details, members] = await Promise.all([
    request<Project>(`/projects/${projectId}`),
    list<ProjectMember>(`/projects/${projectId}/members`),
  ])
  project.value = details
  projectMembers.value = members
}

function refreshMemberships() {
  const workspaceId = workspace.value?.id
  const projectId = project.value?.id
  return run(async () => {
    await refreshWorkspaces(workspaceId)
    if (projectId && projects.value.some((item) => item.id === projectId)) {
      await selectProject(projectId)
    }
  })
}

function createWorkspace() {
  return run(async () => {
    const created = await command<Workspace>('/workspaces', { name: workspaceName.value.trim() })
    workspaceName.value = ''
    await refreshWorkspaces(created.id)
  })
}

function createProject(workspaceId: string) {
  return run(async () => {
    const path = `/workspaces/${workspaceId}/projects`
    const created = await command<Project>(path, {
      name: projectName.value.trim(),
      goal: projectGoal.value.trim(),
    })
    projectName.value = ''
    projectGoal.value = ''
    projects.value = await list<Project>(path)
    await selectProject(created.id)
  })
}

function addWorkspaceMember(workspaceId: string) {
  return run(async () => {
    const path = `/workspaces/${workspaceId}/members`
    await command<WorkspaceMember>(path, {
      loginName: memberLogin.value.trim().toLowerCase(),
      role: workspaceRole.value,
    })
    memberLogin.value = ''
    workspaceMembers.value = await list<WorkspaceMember>(path)
  })
}

function addProjectMember(projectId: string) {
  return run(async () => {
    const path = `/projects/${projectId}/members`
    await command<ProjectMember>(path, { workspaceMemberId: projectMemberId.value, role: projectRole.value })
    projectMemberId.value = ''
    projectMembers.value = await list<ProjectMember>(path)
  })
}

onMounted(() => run(refreshWorkspaces))
</script>

<template>
  <main class="directory">
    <header>
      <h1>项目交付协作平台</h1>
      <div class="inline">
        <span>{{ user.displayName }}</span>
        <button :disabled="signingOut || busy" @click="emit('logout')">退出登录</button>
      </div>
    </header>

    <p v-if="error || logoutError" class="error" role="alert">{{ error || logoutError }}</p>
    <p v-if="busy" role="status">正在处理…</p>

    <div class="layout">
      <section class="panel">
        <h2>我的空间</h2>
        <ul v-if="workspaces.length" class="links">
          <li v-for="item in workspaces" :key="item.id">
            <button
              :aria-pressed="item.id === workspace?.id"
              :disabled="busy"
              @click="run(() => selectWorkspace(item))"
            >{{ item.name }}</button>
          </li>
        </ul>
        <form @submit.prevent="createWorkspace">
          <label>空间名称 <input v-model="workspaceName" required maxlength="120"></label>
          <button :disabled="busy">创建空间</button>
        </form>
      </section>

      <div v-if="workspace" class="content">
        <section class="panel">
          <h2>{{ workspace.name }}</h2>
          <p>{{ roleNames[workspace.currentRole] }}</p>

          <h3>我的项目</h3>
          <ul v-if="projects.length" class="links">
            <li v-for="item in projects" :key="item.id">
              <button
                :aria-pressed="item.id === project?.id"
                :disabled="busy"
                @click="run(() => selectProject(item.id))"
              >{{ item.name }}</button>
            </li>
          </ul>
          <p v-else>这个空间暂时没有你可以访问的项目。</p>
          <form @submit.prevent="createProject(workspace.id)">
            <label>项目名称 <input v-model="projectName" required maxlength="120"></label>
            <label>项目目标 <textarea v-model="projectGoal" required maxlength="60000"></textarea></label>
            <button :disabled="busy">创建项目</button>
          </form>

          <h3>空间成员</h3>
          <ul>
            <li v-for="member in workspaceMembers" :key="member.id">
              {{ member.user.displayName }}（{{ member.user.loginName }}） · {{ roleNames[member.role] }}
              <span v-if="member.status === 'INACTIVE'"> · 已失效</span>
              <MemberAccessActions
                :key="member.version"
                scope="workspace"
                :scope-id="workspace.id"
                :member="member"
                :manager="workspace.currentRole === 'ADMIN'"
                :self="member.user.id === user.id"
                @changed="refreshMemberships"
                @session-expired="emit('sessionExpired')"
              />
            </li>
          </ul>
          <form v-if="workspace.currentRole === 'ADMIN'" @submit.prevent="addWorkspaceMember(workspace.id)">
            <label>已有账号 <input v-model="memberLogin" required maxlength="64"></label>
            <label>空间角色
              <select v-model="workspaceRole">
                <option value="MEMBER">空间成员</option>
                <option value="ADMIN">空间管理员</option>
              </select>
            </label>
            <button :disabled="busy">添加到空间</button>
          </form>
        </section>

        <section v-if="project" class="panel">
          <h2>{{ project.name }}</h2>
          <p>{{ roleNames[project.currentRole] }}<span v-if="project.status === 'ARCHIVED'"> · 已归档</span></p>
          <p class="goal">{{ project.goal }}</p>
          <TaskPanel
            :key="project.id"
            :project="project"
            :members="projectMembers"
            :requested-task="requestedTask"
            @session-expired="emit('sessionExpired')"
          />
          <DocumentPanel :key="project.id" :project="project" :members="projectMembers" @session-expired="emit('sessionExpired')" />
          <h3>项目成员</h3>
          <ul>
            <li v-for="member in projectMembers" :key="member.id">
              {{ member.user.displayName }} · {{ roleNames[member.role] }}
              <span v-if="!member.effective"> · 已失效</span>
              <MemberAccessActions
                :key="member.version"
                scope="project"
                :scope-id="project.id"
                :member="member"
                :manager="project.currentRole === 'OWNER'"
                :self="member.user.id === user.id"
                @changed="refreshMemberships"
                @session-expired="emit('sessionExpired')"
                @focus-task="requestedTask = { id: $event }"
              />
            </li>
          </ul>
          <form
            v-if="project.currentRole === 'OWNER' && project.status === 'ACTIVE'"
            @submit.prevent="addProjectMember(project.id)"
          >
            <label>从空间成员中选择
              <select v-model="projectMemberId" required>
                <option disabled value="">请选择成员</option>
                <option v-for="member in candidates" :key="member.id" :value="member.id">
                  {{ member.user.displayName }}（{{ member.user.loginName }}）
                </option>
              </select>
            </label>
            <label>项目角色
              <select v-model="projectRole">
                <option value="CONTRIBUTOR">执行成员</option>
                <option value="OBSERVER">观察成员</option>
                <option value="OWNER">项目负责人</option>
              </select>
            </label>
            <button :disabled="busy || !candidates.length">添加到项目</button>
          </form>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.directory {
  min-height: 100vh;
  background: #f3f6fa;
  color: #142438;
  font-family: Inter, system-ui, sans-serif;
  padding: clamp(1rem, 3vw, 2.5rem);
  box-sizing: border-box;
}

header,
.layout {
  max-width: 78rem;
  margin: 0 auto 1.25rem;
}

header,
.inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.7rem;
}

header {
  justify-content: space-between;
}

h1, h2, h3 { margin-top: 0; }
h1 { font-size: 1.45rem; margin-bottom: 0; }
h2 { font-size: 1.25rem; }

.layout {
  display: grid;
  align-items: start;
  grid-template-columns: minmax(12rem, 1fr) minmax(0, 3fr);
  gap: 1rem;
}

.content, form, label {
  display: grid;
  gap: 0.65rem;
}

.panel {
  background: white;
  border: 1px solid #dce3ec;
  border-radius: 0.8rem;
  padding: 1.2rem;
}

form { max-width: 32rem; margin: 0.75rem 0 1.25rem; }
input, textarea, select, button { font: inherit; padding: 0.45rem 0.65rem; }
button { cursor: pointer; justify-self: start; }
button:disabled { cursor: default; }
button[aria-pressed="true"] { background: #dce9f9; }
.links { list-style: none; padding: 0; display: grid; gap: 0.3rem; }
.goal { white-space: pre-wrap; overflow-wrap: anywhere; }
.error { color: #a52832; }

@media (max-width: 44rem) {
  .layout { grid-template-columns: minmax(0, 1fr); }
}
</style>

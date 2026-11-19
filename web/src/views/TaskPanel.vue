<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ApiError, request } from '../api/auth'
import { createCommandSender } from '../api/commands'
import type { Project, ProjectMember } from '../api/directory'
import { getTasks, taskStatusNames, type Task, type TaskPage, type TaskScope, type TaskStatus } from '../api/tasks'
import TaskScopeFields from './TaskScopeFields.vue'
import SubmissionPanel from './SubmissionPanel.vue'

const props = defineProps<{ project: Project; members: ProjectMember[]; requestedTask?: { id: string } }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const path = `/projects/${props.project.id}/tasks`
const command = createCommandSender()
const page = ref<TaskPage>()
const filter = ref<TaskStatus | ''>('')
const selected = ref<Task>()
const details = ref<HTMLElement>()
const newScope = ref(emptyScope())
const editScope = ref(emptyScope())
const editReason = ref('')
const assignee = ref<string | null>(null)
const reviewer = ref<string | null>(null)
const assignmentReason = ref('')
const creating = ref(false)
const editing = ref(false)
const busy = ref(false)
const error = ref('')
const stale = ref(false)
const questionsChecked = ref(false)

const canCreate = computed(() => props.project.status === 'ACTIVE' && props.project.currentRole !== 'OBSERVER')
const eligible = computed(() => props.members.filter((member) => member.effective && member.role !== 'OBSERVER'))

function emptyScope(): TaskScope {
  return { title: '', goal: '', expectedOutcome: '', acceptanceCriteria: [], openQuestions: [] }
}

function scopeFrom(task: TaskScope): TaskScope {
  return {
    title: task.title.trim(),
    goal: task.goal.trim(),
    expectedOutcome: task.expectedOutcome.trim(),
    acceptanceCriteria: task.acceptanceCriteria.filter((entry) => entry.text.trim() !== '')
      .map((entry) => ({ key: entry.key, text: entry.text.trim() })),
    openQuestions: task.openQuestions.filter((entry) => entry.text.trim() !== '')
      .map((entry) => ({ key: entry.key, text: entry.text.trim() })),
  }
}

function memberName(id: string | null) {
  if (id === null) return '未分派'
  return props.members.find((member) => member.id === id)?.user.displayName ?? id
}

function invalidDuty(id: string | null) {
  return id !== null && selected.value?.status !== 'DONE' && selected.value?.status !== 'CANCELLED'
    && !eligible.value.some((member) => member.id === id)
}

async function run(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await operation()
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      emit('sessionExpired')
    } else {
      stale.value ||= failure instanceof ApiError
        && (failure.code === 'VERSION_CONFLICT' || failure.code === 'ALREADY_ASSIGNED' || failure.code === 'STATE_CONFLICT')
      error.value = failure instanceof ApiError ? failure.message : '暂时无法连接后端，可以重试原操作'
    }
  } finally {
    busy.value = false
  }
}

async function loadPage(next = 1) {
  page.value = await getTasks(path, next, filter.value)
}

async function selectTask(id: string) {
  const task = await request<Task>(`${path}/${id}`)
  selected.value = task
  editScope.value = scopeFrom(task)
  editReason.value = ''
  assignee.value = task.assigneeMemberId
  reviewer.value = task.reviewerMemberId
  assignmentReason.value = ''
  editing.value = false
  stale.value = false
  questionsChecked.value = false
}

function createTask() {
  return run(async () => {
    const created = await command<Task>(path, scopeFrom(newScope.value))
    newScope.value = emptyScope()
    creating.value = false
    filter.value = ''
    await selectTask(created.id)
    await loadPage()
  })
}

function saveScope(task: Task) {
  return run(async () => {
    await command(`${path}/${task.id}/scope`, {
      ...scopeFrom(editScope.value),
      expectedVersion: task.version,
      reason: editReason.value.trim(),
    }, 'PATCH')
    await selectTask(task.id)
    await loadPage(page.value?.page)
  })
}

function advanceTask(task: Task, action: 'ready' | 'claim' | 'start') {
  return run(async () => {
    await command(`${path}/${task.id}/${action}`, { expectedVersion: task.version })
    await selectTask(task.id)
    await loadPage(page.value?.page)
  })
}

function assign(task: Task) {
  return run(async () => {
    await command(`${path}/${task.id}/assignment`, {
      expectedVersion: task.version,
      assigneeMemberId: assignee.value,
      reviewerMemberId: reviewer.value,
      reason: assignmentReason.value.trim(),
    }, 'PUT')
    await selectTask(task.id)
    await loadPage(page.value?.page)
  })
}

onMounted(() => run(() => loadPage()))
watch(() => props.requestedTask, (task) => {
  if (task) void run(async () => {
    await selectTask(task.id)
    await nextTick()
    details.value?.scrollIntoView({ block: 'start' })
  })
})
</script>

<template>
  <section class="tasks" aria-labelledby="tasks-title">
    <div class="actions">
      <h3 id="tasks-title">任务</h3>
      <button v-if="canCreate && !creating" type="button" :disabled="busy" @click="creating = true">新建任务</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在处理任务…</p>

    <form v-if="creating && canCreate" class="scope" @submit.prevent="createTask">
      <h4>新建待梳理任务</h4>
      <TaskScopeFields v-model="newScope" />
      <div class="actions">
        <button :disabled="busy">创建任务</button>
        <button type="button" :disabled="busy" @click="creating = false">收起</button>
      </div>
    </form>

    <div class="actions">
      <label>任务状态
        <select v-model="filter" :disabled="busy" @change="run(() => loadPage())">
          <option value="">全部状态</option>
          <option v-for="(label, status) in taskStatusNames" :key="status" :value="status">{{ label }}</option>
        </select>
      </label>
    </div>
    <p v-if="page && !page.items.length">当前筛选下还没有任务。</p>
    <ul v-else-if="page" class="task-list">
      <li v-for="task in page.items" :key="task.id">
        <button type="button" :disabled="busy" :aria-pressed="task.id === selected?.id"
          @click="run(() => selectTask(task.id))">{{ task.title }}</button>
        <span>{{ taskStatusNames[task.status] }}</span>
      </li>
    </ul>
    <div v-if="page && (page.page > 1 || page.total > page.pageSize)" class="actions">
      <button type="button" :disabled="busy || page.page === 1" @click="run(() => loadPage(page!.page - 1))">上一页</button>
      <span>第 {{ page.page }} 页</span>
      <button type="button" :disabled="busy || page.page * page.pageSize >= page.total"
        @click="run(() => loadPage(page!.page + 1))">下一页</button>
    </div>

    <article v-if="selected" ref="details" class="task-detail">
      <div class="actions">
        <h4>{{ selected.title }}</h4>
        <span>{{ taskStatusNames[selected.status] }} · 版本 {{ selected.version }}</span>
      </div>
      <p v-if="stale">表单内容已保留。载入最新版本会重新填充表单。</p>
      <button v-if="stale" type="button" :disabled="busy" @click="run(() => selectTask(selected!.id))">
        载入最新版本
      </button>

      <dl>
        <dt>目标</dt><dd>{{ selected.goal || '待补充' }}</dd>
        <dt>预期成果</dt><dd>{{ selected.expectedOutcome || '待补充' }}</dd>
        <dt>执行者</dt><dd>{{ memberName(selected.assigneeMemberId) }}<span v-if="invalidDuty(selected.assigneeMemberId)"> · 职责失效</span></dd>
        <dt>确认人</dt><dd>{{ memberName(selected.reviewerMemberId) }}<span v-if="invalidDuty(selected.reviewerMemberId)"> · 职责失效</span></dd>
      </dl>
      <p v-if="project.currentRole === 'OWNER' && selected.status === 'IN_REVIEW' && invalidDuty(selected.assigneeMemberId)">
        执行者的职责已失效。可先撤回本轮成果，再更换执行者。
      </p>
      <template v-if="selected.acceptanceCriteria.length">
        <p>完成条件</p>
        <ul><li v-for="item in selected.acceptanceCriteria" :key="item.key">{{ item.text }}</li></ul>
      </template>
      <template v-if="selected.openQuestions.length">
        <p>待确认疑问</p>
        <ul><li v-for="item in selected.openQuestions" :key="item.key">{{ item.text }}</li></ul>
      </template>

      <div class="actions">
        <button v-if="selected.allowedActions.includes('EDIT_SCOPE') && !editing" type="button" :disabled="busy || stale"
          @click="editing = true">编辑范围</button>
        <template v-if="selected.allowedActions.includes('READY') && !editing">
          <label v-if="selected.openQuestions.length">
            <input v-model="questionsChecked" type="checkbox"> 已解决影响范围或验收的疑问
          </label>
          <button type="button" :disabled="busy || stale || (selected.openQuestions.length > 0 && !questionsChecked)"
            @click="advanceTask(selected, 'ready')">整理就绪</button>
        </template>
        <button v-if="selected.allowedActions.includes('CLAIM') && !editing" type="button" :disabled="busy || stale"
          @click="advanceTask(selected, 'claim')">领取任务</button>
        <button v-if="selected.allowedActions.includes('START') && !editing" type="button" :disabled="busy || stale"
          @click="advanceTask(selected, 'start')">开始执行</button>
      </div>
      <form v-if="editing && selected.allowedActions.includes('EDIT_SCOPE')" class="scope" @submit.prevent="saveScope(selected)">
        <TaskScopeFields v-model="editScope" />
        <label v-if="selected.status === 'READY'">修改原因 <input v-model="editReason" required maxlength="60000"></label>
        <div class="actions">
          <button :disabled="busy || stale">保存范围</button>
          <button type="button" :disabled="busy" @click="editing = false">收起</button>
        </div>
      </form>
      <form v-if="selected.allowedActions.includes('ASSIGN')" class="actions assignment" @submit.prevent="assign(selected)">
        <label>执行者
          <select v-model="assignee" :disabled="selected.status === 'IN_REVIEW'">
            <option :value="null">未分派</option>
            <option v-for="member in eligible" :key="member.id" :value="member.id">{{ member.user.displayName }}</option>
          </select>
        </label>
        <label>确认人
          <select v-model="reviewer">
            <option v-if="selected.status !== 'IN_REVIEW'" :value="null">未分派</option>
            <option v-for="member in eligible" :key="member.id" :value="member.id">{{ member.user.displayName }}</option>
          </select>
        </label>
        <label v-if="selected.status === 'IN_PROGRESS' && assignee !== selected.assigneeMemberId">
          移交原因 <input v-model="assignmentReason" required maxlength="60000">
        </label>
        <button :disabled="busy || stale">保存分派</button>
      </form>
      <SubmissionPanel
        :project-id="project.id"
        v-if="['IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELLED'].includes(selected.status)"
        :key="selected.id"
        :task="selected"
        :task-path="`${path}/${selected.id}`"
        :members="members"
        @session-expired="emit('sessionExpired')"
        @updated="run(async () => { await selectTask(selected!.id); await loadPage(page?.page) })"
      />
    </article>
  </section>
</template>

<style scoped>
.tasks, .task-detail, .assignment {
  margin-block: 1rem;
  padding-top: 0.75rem;
  border-top: 1px solid #dce3ec;
}
.actions, .task-list li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.65rem;
}
h3, h4 { margin: 0; }
.scope, .scope > label {
  display: grid;
  gap: 0.55rem;
}
.scope { margin-block: 0.7rem; max-width: 36rem; }
.task-list { list-style: none; padding: 0; display: grid; gap: 0.4rem; }
button, select, input { font: inherit; padding: 0.45rem 0.65rem; }
button[aria-pressed="true"] { background: #dce9f9; }
dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.4rem 0.85rem; }
dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.error { color: #a52832; }
</style>

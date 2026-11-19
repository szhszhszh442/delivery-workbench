<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError, request } from '../api/auth'
import { createCommandSender } from '../api/commands'
import { list, type ProjectMember } from '../api/directory'
import { documentsPath, type Revision } from '../api/documents'
import {
  submissionStatusNames, type MaterialKind, type Submission, type SubmissionMaterial,
  type SubmissionResult, type SubmissionSummary,
} from '../api/submissions'
import type { Task } from '../api/tasks'
import DocumentRevisionPicker from './DocumentRevisionPicker.vue'

interface MaterialDraft {
  key: string
  kind: MaterialKind
  label: string
  value: string
}

const props = defineProps<{ projectId: string; task: Task; taskPath: string; members: ProjectMember[] }>()
const emit = defineEmits<{ updated: []; sessionExpired: [] }>()
const command = createCommandSender()
const path = `${props.taskPath}/submissions`
const history = ref<SubmissionSummary[]>([])
const detail = ref<Submission>()
const summary = ref('')
const materials = ref<MaterialDraft[]>([materialDraft()])
const opinion = ref('')
const withdrawalReason = ref('')
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const preview = ref<{ materialId: string; revision: Revision }>()
const active = computed(() => detail.value?.status === 'PENDING'
  && detail.value.id === props.task.currentSubmissionId)
let latestRead = 0

function materialDraft(): MaterialDraft {
  return { key: crypto.randomUUID(), kind: 'TEXT', label: '', value: '' }
}

function memberName(id: string) {
  return props.members.find((member) => member.id === id)?.user.displayName ?? id
}

function showSubmission(next: Submission | undefined) {
  if (next?.id !== detail.value?.id) {
    opinion.value = ''
    withdrawalReason.value = ''
    preview.value = undefined
  }
  detail.value = next
}

function handleFailure(failure: unknown) {
  if (failure instanceof ApiError && failure.status === 401) {
    emit('sessionExpired')
  } else {
    conflict.value = failure instanceof ApiError
      && (failure.code === 'SUBMISSION_STALE' || failure.code === 'VERSION_CONFLICT' || failure.code === 'STATE_CONFLICT')
    error.value = failure instanceof ApiError ? failure.message : '连接中断，可用相同内容重试原操作'
  }
}

async function load(preferred?: string | null) {
  const read = ++latestRead
  try {
    const entries = await list<SubmissionSummary>(path)
    const next = entries.find((item) => item.id === preferred) ?? entries[0]
    const selected = next ? await request<Submission>(`${path}/${next.id}`) : undefined
    if (read === latestRead) {
      history.value = entries
      showSubmission(selected)
    }
  } catch (failure) {
    if (read === latestRead) handleFailure(failure)
  }
}

async function view(id: string) {
  if (busy.value) return
  const read = ++latestRead
  error.value = ''
  try {
    const current = await request<Submission>(`${path}/${id}`)
    if (read === latestRead) {
      showSubmission(current)
    }
  } catch (failure) {
    if (read === latestRead) handleFailure(failure)
  }
}

async function perform(write: () => Promise<SubmissionResult>, reset: () => void) {
  if (busy.value || conflict.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await write()
    reset()
    await load(result.submission.id)
    emit('updated')
  } catch (failure) {
    handleFailure(failure)
  } finally {
    busy.value = false
  }
}

function submit() {
  return perform(() => command(path, {
    expectedVersion: props.task.version,
    summary: summary.value.trim(),
    materials: materials.value.map((item) => ({
      kind: item.kind,
      label: item.label.trim(),
      ...materialPayload(item),
    })),
  }), () => {
    summary.value = ''
    materials.value = [materialDraft()]
  })
}

function materialPayload(item: MaterialDraft) {
  switch (item.kind) {
    case 'TEXT': return { body: item.value.trim() }
    case 'LINK': return { url: item.value.trim() }
    case 'DOCUMENT': return { documentRevisionId: item.value }
  }
}

async function viewMaterial(material: SubmissionMaterial) {
  if (busy.value || !material.documentId || !material.documentRevisionId) return
  if (preview.value?.materialId === material.id) {
    preview.value = undefined
    return
  }
  busy.value = true
  error.value = ''
  const submission = detail.value?.id
  try {
    const revision = await request<Revision>(
      `${documentsPath(props.projectId)}/${material.documentId}/revisions/${material.documentRevisionId}`,
    )
    if (submission === detail.value?.id) preview.value = { materialId: material.id, revision }
  } catch (failure) {
    handleFailure(failure)
  } finally {
    busy.value = false
  }
}

function finish(action: 'approve' | 'return' | 'withdraw') {
  const current = detail.value
  if (!current || !active.value) return
  return perform(() => command(`${path}/${current.id}/${action}`, {
    expectedVersion: props.task.version,
    ...(action === 'withdraw' ? { reason: withdrawalReason.value.trim() } : { comment: opinion.value.trim() }),
  }), () => {
    opinion.value = ''
    withdrawalReason.value = ''
  })
}

function refresh() {
  if (busy.value) return
  error.value = ''
  emit('updated')
  void load(props.task.currentSubmissionId ?? detail.value?.id)
}

watch(() => props.task, (task) => {
  conflict.value = false
  void load(task.currentSubmissionId ?? detail.value?.id)
}, { immediate: true })
</script>

<template>
  <section class="submissions">
    <header class="controls">
      <h4>成果提交</h4>
      <button type="button" :disabled="busy" @click="refresh">刷新成果</button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="conflict">刷新任务和成果后，再核对本轮材料与处理状态。</p>

    <form v-if="task.allowedActions.includes('SUBMIT')" class="form" @submit.prevent="submit">
      <p v-if="!task.reviewerMemberId">请先由负责人分派成果确认人。</p>
      <label>成果说明 <textarea v-model="summary" rows="3" maxlength="60000" required></textarea></label>
      <fieldset v-for="(material, index) in materials" :key="material.key" class="form">
        <legend>材料 {{ index + 1 }}</legend>
        <label>类型
          <select v-model="material.kind" @change="material.value = ''">
            <option value="TEXT">成果正文</option>
            <option value="LINK">成果链接</option>
            <option value="DOCUMENT">固定文档版本</option>
          </select>
        </label>
        <label>名称 <input v-model="material.label" required maxlength="200"></label>
        <label v-if="material.kind === 'TEXT'">
          正文 <textarea v-model="material.value" rows="2" maxlength="60000" required></textarea>
        </label>
        <label v-else-if="material.kind === 'LINK'">链接 <input v-model="material.value" type="url" maxlength="2048" required></label>
        <DocumentRevisionPicker v-else v-model="material.value" :project-id="projectId" :disabled="busy"
          @session-expired="emit('sessionExpired')" />
        <button type="button" :disabled="busy || materials.length === 1" @click="materials.splice(index, 1)">
          移除材料
        </button>
      </fieldset>
      <div class="controls">
        <button type="button" :disabled="busy || materials.length >= 20" @click="materials.push(materialDraft())">
          添加材料
        </button>
        <button :disabled="busy || conflict || !task.reviewerMemberId || materials.some((item) => !item.value.trim())">提交成果</button>
      </div>
    </form>

    <p v-if="!history.length">暂无成果记录。</p>
    <ul v-else class="rounds">
      <li v-for="item in history" :key="item.id">
        <button type="button" :aria-pressed="detail?.id === item.id" :disabled="busy" @click="view(item.id)">
          第 {{ item.submissionNo }} 轮 · {{ submissionStatusNames[item.status] }}
        </button>
      </li>
    </ul>
    <article v-if="detail" class="round">
      <h5>第 {{ detail.submissionNo }} 轮成果</h5>
      <p class="text">{{ detail.summary }}</p>
      <p>提交人 {{ memberName(detail.submittedByMemberId) }} · 提交时确认人 {{ memberName(detail.reviewerSnapshotMemberId) }}</p>
      <p v-if="detail.decision">
        {{ memberName(detail.decision.reviewerMemberId) }}{{ detail.decision.decision === 'APPROVE' ? '确认通过' : '退回修改' }}
      </p>
      <p v-if="detail.decision?.comment" class="text">{{ detail.decision.comment }}</p>
      <p v-if="detail.endReason" class="text">
        {{ detail.endedByMemberId ? memberName(detail.endedByMemberId) : '' }}撤回，原因是{{ detail.endReason }}
      </p>
      <details>
        <summary>提交时的完成条件</summary>
        <ul><li v-for="entry in detail.scopeSnapshot.acceptanceCriteria" :key="entry.key">{{ entry.text }}</li></ul>
      </details>
      <ul>
        <li v-for="item in detail.materials" :key="item.id">
          <a v-if="item.kind === 'LINK' && item.url" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.label }}</a>
          <template v-else-if="item.kind === 'DOCUMENT'">
            <button type="button" :disabled="busy" :aria-expanded="preview?.materialId === item.id"
              @click="viewMaterial(item)">{{ item.label }} · v{{ item.revisionNo }}</button>
            <div v-if="preview && preview.materialId === item.id">
              <strong>{{ preview.revision.title }}</strong>
              <pre>{{ preview.revision.contentMarkdown }}</pre>
            </div>
          </template>
          <template v-else>{{ item.label }}<p class="text">{{ item.body }}</p></template>
        </li>
      </ul>

      <div v-if="active && task.allowedActions.includes('APPROVE')" class="form">
        <label>确认意见 <textarea v-model="opinion" rows="2" maxlength="60000"></textarea></label>
        <div class="controls">
          <button type="button" :disabled="busy || conflict" @click="finish('approve')">确认通过</button>
          <button type="button" :disabled="busy || conflict || !opinion.trim()" @click="finish('return')">退回修改</button>
        </div>
      </div>
      <form v-if="active && task.allowedActions.includes('WITHDRAW')" class="form" @submit.prevent="finish('withdraw')">
        <label>撤回原因 <input v-model="withdrawalReason" maxlength="60000" required></label>
        <button :disabled="busy || conflict || !withdrawalReason.trim()">撤回本轮提交</button>
      </form>
    </article>
  </section>
</template>

<style scoped>
.submissions, .round { margin-block: 1rem; padding-top: 0.75rem; border-top: 1px solid #dce3ec; }
h4, h5 { margin: 0; }
.controls, .rounds { display: flex; align-items: center; flex-wrap: wrap; gap: 0.55rem; }
.rounds { padding-left: 0; list-style: none; }
.form, label { display: grid; gap: 0.55rem; }
.form { max-width: 36rem; margin-block: 0.75rem; }
fieldset { border: 1px solid #dce3ec; min-width: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 0.75rem; background: #f2f5f9; }
button, input, textarea, select { font: inherit; padding: 0.45rem 0.65rem; }
button { justify-self: start; }
button[aria-pressed="true"] { background: #dce9f9; }
.text { white-space: pre-wrap; overflow-wrap: anywhere; }
.error { color: #a52832; }
</style>

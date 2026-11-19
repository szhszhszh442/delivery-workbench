<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, request } from '../api/auth'
import { createCommandSender } from '../api/commands'
import { list, type Project, type ProjectMember } from '../api/directory'
import {
  documentKinds, documentsPath, type DocumentKind, type DocumentSummary, type ProjectDocument,
  type Revision, type RevisionSummary,
} from '../api/documents'

const props = defineProps<{ project: Project; members: ProjectMember[] }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const path = documentsPath(props.project.id)
const command = createCommandSender()
const entries = ref<DocumentSummary[]>([])
const revisions = ref<RevisionSummary[]>([])
const current = ref<ProjectDocument>()
const preview = ref<Revision>()
const draft = ref<{ base?: ProjectDocument; kind: DocumentKind; title: string; contentMarkdown: string; changeNote: string }>()
const newest = ref<ProjectDocument>()
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const canEdit = computed(() => props.project.status === 'ACTIVE' && props.project.currentRole !== 'OBSERVER')

function handle(failure: unknown) {
  if (failure instanceof ApiError && failure.status === 401) {
    emit('sessionExpired')
  } else {
    error.value = failure instanceof ApiError ? failure.message : '连接中断，请重试'
  }
}

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (failure) {
    handle(failure)
  } finally {
    busy.value = false
  }
}

async function load(id?: string) {
  entries.value = await list<DocumentSummary>(path)
  if (!id) return
  const [document, history] = await Promise.all([
    request<ProjectDocument>(`${path}/${id}`),
    list<RevisionSummary>(`${path}/${id}/revisions`),
  ])
  current.value = document
  revisions.value = history
  preview.value = document.currentRevision
}

function start(base?: ProjectDocument) {
  error.value = ''
  draft.value = {
    base, kind: base?.kind ?? 'GENERAL', title: base?.title ?? '',
    contentMarkdown: base?.currentRevision.contentMarkdown ?? '', changeNote: '',
  }
}

function cancel() {
  draft.value = undefined
  conflict.value = false
  newest.value = undefined
  error.value = ''
}

function save() {
  const value = draft.value
  if (!value || conflict.value || !canEdit.value) return
  return run(async () => {
    const { base, kind, title, contentMarkdown, changeNote } = value
    const content = { title, contentMarkdown, changeNote }
    let result: ProjectDocument
    try {
      result = base
        ? await command<ProjectDocument>(`${path}/${base.id}/revisions`, {
          ...content, expectedVersion: base.version, baseRevisionId: base.currentRevision.id,
        })
        : await command<ProjectDocument>(path, { ...content, kind })
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'VERSION_CONFLICT') {
        conflict.value = true
      }
      throw failure
    }
    cancel()
    current.value = result
    preview.value = result.currentRevision
    revisions.value = []
    await load(result.id)
  })
}

function compare() {
  const id = draft.value?.base?.id
  if (id) return run(async () => { newest.value = await request<ProjectDocument>(`${path}/${id}`) })
}

function useLatestBase() {
  if (!draft.value || !newest.value) return
  draft.value.base = newest.value
  newest.value = undefined
  conflict.value = false
  error.value = ''
}

function viewRevision(revision: RevisionSummary) {
  return run(async () => {
    preview.value = await request<Revision>(`${path}/${revision.documentId}/revisions/${revision.id}`)
  })
}

function editorName(id: string) {
  return props.members.find((member) => member.id === id)?.user.displayName ?? id
}

onMounted(() => run(load))
</script>

<template>
  <section class="documents">
    <header>
      <h3>项目文档</h3>
      <button type="button" :disabled="busy || !!draft" @click="run(() => load(current?.id))">刷新文档</button>
      <button v-if="canEdit" type="button" :disabled="busy || !!draft" @click="start()">新建文档</button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="!entries.length && !draft">暂无文档。</p>
    <ul v-if="entries.length" class="choices">
      <li v-for="entry in entries" :key="entry.id">
        <button type="button" :disabled="busy || !!draft" :aria-pressed="current?.id === entry.id"
          @click="run(() => load(entry.id))">{{ entry.title }} · {{ documentKinds[entry.kind] }} · v{{ entry.revisionNo }}</button>
      </li>
    </ul>

    <form v-if="draft" @submit.prevent="save">
      <fieldset :disabled="busy || !canEdit">
        <legend>{{ draft.base ? '保存新版本' : '新建文档' }}</legend>
        <label v-if="!draft.base">类型
          <select v-model="draft.kind">
            <option v-for="(name, kind) in documentKinds" :key="kind" :value="kind">{{ name }}</option>
          </select>
        </label>
        <label>标题 <input v-model="draft.title" required maxlength="200"></label>
        <label>正文（Markdown）<textarea v-model="draft.contentMarkdown" rows="8" required maxlength="60000"></textarea></label>
        <label>变更说明 <input v-model="draft.changeNote" maxlength="500"></label>
      </fieldset>
      <div v-if="conflict">
        <p>已保留你的稿件。请对照最新正文，合并需要保留的内容，再确认采用新的保存基准。</p>
        <button type="button" :disabled="busy" @click="compare">查看最新版本</button>
        <div v-if="newest">
          <h4>v{{ newest.currentRevision.revisionNo }} · {{ newest.title }}</h4>
          <pre>{{ newest.currentRevision.contentMarkdown }}</pre>
          <button type="button" :disabled="busy || !canEdit" @click="useLatestBase">保留我的稿件，采用此保存基准</button>
        </div>
      </div>
      <div class="choices">
        <button :disabled="busy || conflict || !canEdit || !draft.title.trim() || !draft.contentMarkdown.trim()">保存</button>
        <button type="button" :disabled="busy" @click="cancel">取消编辑</button>
      </div>
    </form>

    <article v-else-if="current && preview">
      <header>
        <h4>{{ preview.title }} · v{{ preview.revisionNo }}</h4>
        <button v-if="canEdit" type="button" :disabled="busy" @click="start(current)">编辑当前版本</button>
      </header>
      <p>{{ editorName(preview.editorMemberId) }} · {{ new Date(preview.createdAt).toLocaleString() }}</p>
      <p v-if="preview.changeNote">{{ preview.changeNote }}</p>
      <pre>{{ preview.contentMarkdown }}</pre>
      <details>
        <summary>版本历史</summary>
        <ul class="choices">
          <li v-for="item in revisions" :key="item.id">
            <button type="button" :disabled="busy" :aria-pressed="preview.id === item.id"
              @click="viewRevision(item)">v{{ item.revisionNo }} · {{ item.title }}</button>
          </li>
        </ul>
      </details>
    </article>
  </section>
</template>

<style scoped>
.documents { border-top: 1px solid #dce3ec; padding-top: 1rem; margin-block: 1.25rem; }
header, .choices { display: flex; gap: 0.55rem; align-items: center; flex-wrap: wrap; }
h3, h4 { margin: 0; }
ul.choices { padding-left: 0; list-style: none; }
form, fieldset, label { display: grid; gap: 0.55rem; }
fieldset { border: 1px solid #dce3ec; min-width: 0; }
form { max-width: 42rem; }
button, input, select, textarea { font: inherit; padding: 0.45rem 0.65rem; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 0.75rem; background: #f2f5f9; }
.error { color: #a02020; }
</style>

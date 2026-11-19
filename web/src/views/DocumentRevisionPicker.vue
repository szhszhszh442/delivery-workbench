<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/auth'
import { list } from '../api/directory'
import { documentsPath, type DocumentSummary, type RevisionSummary } from '../api/documents'

const props = defineProps<{ projectId: string; disabled: boolean }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const model = defineModel<string>({ required: true })
const path = documentsPath(props.projectId)
const documents = ref<DocumentSummary[]>([])
const revisions = ref<RevisionSummary[]>([])
const documentId = ref('')
const busy = ref(false)
const error = ref('')

async function load() {
  busy.value = true
  error.value = ''
  try {
    if (documentId.value) {
      revisions.value = await list<RevisionSummary>(`${path}/${documentId.value}/revisions`)
    } else {
      documents.value = await list<DocumentSummary>(path)
    }
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      emit('sessionExpired')
    } else {
      error.value = failure instanceof ApiError ? failure.message : '无法读取文档版本'
    }
  } finally {
    busy.value = false
  }
}

function chooseDocument() {
  model.value = ''
  revisions.value = []
  void load()
}

onMounted(load)
</script>

<template>
  <label>文档
    <select v-model="documentId" :disabled="disabled || busy" required @change="chooseDocument">
      <option value="">选择项目文档</option>
      <option v-for="document in documents" :key="document.id" :value="document.id">{{ document.title }}</option>
    </select>
  </label>
  <label v-if="documentId">固定版本
    <select v-model="model" :disabled="disabled || busy" required>
      <option value="">选择具体版本</option>
      <option v-for="revision in revisions" :key="revision.id" :value="revision.id">v{{ revision.revisionNo }} · {{ revision.title }}</option>
    </select>
  </label>
  <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="disabled || busy" @click="load">重试</button></p>
</template>

<style scoped>
label { display: grid; gap: 0.55rem; }
select, button { font: inherit; padding: 0.45rem 0.65rem; }
</style>

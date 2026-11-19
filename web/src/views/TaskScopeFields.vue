<script setup lang="ts">
import type { TaskScope } from '../api/tasks'

const scope = defineModel<TaskScope>({ required: true })
const groups = [
  { field: 'acceptanceCriteria', label: '完成条件' },
  { field: 'openQuestions', label: '待确认疑问' },
] as const

function add(field: (typeof groups)[number]['field']) {
  scope.value[field].push({ key: crypto.randomUUID(), text: '' })
}
</script>

<template>
  <label>任务标题 <input v-model="scope.title" required maxlength="200"></label>
  <label>任务目标 <textarea v-model="scope.goal" rows="2" maxlength="60000"></textarea></label>
  <label>预期成果 <textarea v-model="scope.expectedOutcome" rows="2" maxlength="60000"></textarea></label>
  <fieldset v-for="{ field, label } in groups" :key="field">
    <legend>{{ label }}</legend>
    <div v-for="(entry, index) in scope[field]" :key="entry.key" class="entry">
      <input v-model="entry.text" :aria-label="`${label} ${index + 1}`" maxlength="60000">
      <button type="button" :aria-label="`移除${label} ${index + 1}`" @click="scope[field].splice(index, 1)">移除</button>
    </div>
    <button type="button" :disabled="scope[field].length >= 20" @click="add(field)">添加{{ label }}</button>
  </fieldset>
</template>

<style scoped>
label {
  display: grid;
  gap: 0.3rem;
}
fieldset {
  border: 1px solid #dce3ec;
  padding: 0.5rem;
  min-width: 0;
}
.entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.4rem;
  margin-bottom: 0.5rem;
}
input, textarea, button {
  font: inherit;
  box-sizing: border-box;
  padding: 0.45rem 0.65rem;
}
textarea {
  resize: vertical;
}
</style>

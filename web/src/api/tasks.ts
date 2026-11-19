import { request } from './auth'

export type TaskStatus = 'DRAFT' | 'READY' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE' | 'CANCELLED'
export type TaskAction = 'EDIT_SCOPE' | 'READY' | 'ASSIGN' | 'CLAIM' | 'START'
  | 'SUBMIT' | 'APPROVE' | 'RETURN' | 'WITHDRAW'

export interface Entry {
  key: string
  text: string
}

export interface TaskScope {
  title: string
  goal: string
  expectedOutcome: string
  acceptanceCriteria: Entry[]
  openQuestions: Entry[]
}

export interface TaskSummary {
  id: string
  title: string
  status: TaskStatus
  assigneeMemberId: string | null
  reviewerMemberId: string | null
  version: number
}

export interface Task extends TaskSummary, TaskScope {
  allowedActions: TaskAction[]
  currentSubmissionId: string | null
}

export interface TaskPage {
  items: TaskSummary[]
  page: number
  pageSize: number
  total: number
}

export const taskStatusNames: Record<TaskStatus, string> = {
  DRAFT: '待梳理',
  READY: '待开始',
  IN_PROGRESS: '进行中',
  IN_REVIEW: '待确认',
  DONE: '已完成',
  CANCELLED: '已取消',
}

export function getTasks(path: string, page: number, status: TaskStatus | ''): Promise<TaskPage> {
  const query = new URLSearchParams({ page: String(page) })
  if (status) query.set('status', status)
  return request(`${path}?${query}`)
}

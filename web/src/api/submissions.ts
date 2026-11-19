import type { Task, TaskScope } from './tasks'

export type SubmissionStatus = 'PENDING' | 'APPROVED' | 'RETURNED' | 'WITHDRAWN' | 'INVALIDATED'
export type MaterialKind = 'TEXT' | 'LINK' | 'DOCUMENT'

export interface SubmissionSummary {
  id: string
  submissionNo: number
  status: SubmissionStatus
  summary: string
}

export interface Submission extends SubmissionSummary {
  submittedByMemberId: string
  reviewerSnapshotMemberId: string
  scopeSnapshot: TaskScope
  materials: SubmissionMaterial[]
  decision: { reviewerMemberId: string; decision: 'APPROVE' | 'RETURN'; comment: string } | null
  endReason: string | null
  endedByMemberId: string | null
}

export interface SubmissionMaterial {
  id: string
  kind: MaterialKind
  label: string
  body: string | null
  url: string | null
  documentId?: string
  documentRevisionId?: string
  revisionNo?: number
}

export interface SubmissionResult {
  task: Task
  submission: Submission
}

export const submissionStatusNames: Record<SubmissionStatus, string> = {
  PENDING: '待确认',
  APPROVED: '已通过',
  RETURNED: '已退回',
  WITHDRAWN: '已撤回',
  INVALIDATED: '已失效',
}

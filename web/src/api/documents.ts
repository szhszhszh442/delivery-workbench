export const documentKinds = {
  REQUIREMENT: '需求',
  PLAN: '方案',
  DECISION: '决定',
  DELIVERY: '成果',
  HANDOVER: '接手说明',
  GENERAL: '其他',
}

export type DocumentKind = keyof typeof documentKinds

export interface DocumentSummary {
  id: string
  kind: DocumentKind
  title: string
  revisionNo: number
}

export interface RevisionSummary {
  id: string
  documentId: string
  revisionNo: number
  title: string
  editorMemberId: string
  changeNote: string
  createdAt: string
}

export interface Revision extends RevisionSummary {
  contentMarkdown: string
}

export interface ProjectDocument {
  id: string
  kind: DocumentKind
  title: string
  version: number
  currentRevision: Revision
}

export function documentsPath(projectId: string) {
  return `/projects/${projectId}/documents`
}

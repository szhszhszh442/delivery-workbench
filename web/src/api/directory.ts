import { request } from './auth'

export type WorkspaceRole = 'ADMIN' | 'MEMBER'
export type ProjectRole = 'OWNER' | 'CONTRIBUTOR' | 'OBSERVER'

export interface Workspace {
  id: string
  name: string
  currentRole: WorkspaceRole
}

export interface Project {
  id: string
  name: string
  goal: string
  status: 'ACTIVE' | 'ARCHIVED'
  currentRole: ProjectRole
}

export interface WorkspaceMember {
  id: string
  version: number
  role: WorkspaceRole
  status: 'ACTIVE' | 'INACTIVE'
  user: { id: string; loginName: string; displayName: string }
}

export interface ProjectMember {
  id: string
  version: number
  workspaceMemberId: string
  role: ProjectRole
  status: 'ACTIVE' | 'INACTIVE'
  effective: boolean
  user: { id: string; displayName: string }
}

interface Page<T> {
  items: T[]
  total: number
}

export async function list<T>(path: string): Promise<T[]> {
  const items: T[] = []
  const pageSize = 100
  for (let page = 1; ; page++) {
    const result = await request<Page<T>>(`${path}?page=${page}&pageSize=${pageSize}`)
    items.push(...result.items)
    if (result.items.length < pageSize || items.length >= result.total) {
      return items
    }
  }
}

import { writeJson } from './auth'

export function createCommandSender() {
  const outstanding = new Map<string, string>()
  return async function command<T>(path: string, body: object, method: 'POST' | 'PATCH' | 'PUT' = 'POST'): Promise<T> {
    const signature = `${method} ${path}\n${JSON.stringify(body)}`
    const key = outstanding.get(signature) ?? crypto.randomUUID()
    outstanding.set(signature, key)
    const result = await writeJson<T>(path, body, key, method)
    outstanding.delete(signature)
    return result
  }
}

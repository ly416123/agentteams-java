export const queryKeys = {
  projects: ['projects'] as const,
  overview: (projectId: string) => ['overview', projectId] as const,
  teams: (projectId: string, filters: object = {}) => ['teams', projectId, filters] as const,
  team: (projectId: string, teamId: string) => ['team', projectId, teamId] as const,
  teamMembers: (projectId: string, teamId: string) => ['team-members', projectId, teamId] as const,
  teamPolicy: (projectId: string, teamId: string) => ['team-policy', projectId, teamId] as const,
  teamRevisions: (projectId: string, teamId: string) =>
    ['team-revisions', projectId, teamId] as const,
  teamDeployments: (projectId: string, teamId: string) =>
    ['team-deployments', projectId, teamId] as const,
  tasks: (projectId: string, filters: object = {}, view = 'table', cursor?: string) =>
    ['tasks', projectId, filters, view, cursor] as const,
  task: (projectId: string, taskId: string) => ['task', projectId, taskId] as const,
  taskEvents: (projectId: string, taskId: string) => ['task-events', projectId, taskId] as const,
  workers: (projectId: string, filters: object = {}) => ['workers', projectId, filters] as const,
  worker: (projectId: string, workerId: string) => ['worker', projectId, workerId] as const,
  workerOperations: (projectId: string, workerId: string, cursor?: string) =>
    ['worker-operations', projectId, workerId, cursor] as const,
  conversations: (projectId: string, cursor?: string) =>
    ['conversations', projectId, cursor] as const,
};

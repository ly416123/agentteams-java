import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthProvider';
import { ProjectProvider } from '../auth/ProjectContext';
import { RequireAuth } from '../auth/RequireAuth';
import { AuthCallbackPage } from '../features/login/AuthCallbackPage';
import { LoginPage } from '../features/login/LoginPage';
import { OverviewPage } from '../features/overview/OverviewPage';
import { TeamCreatePage } from '../features/teams/TeamCreatePage';
import { TeamDetailPage } from '../features/teams/TeamDetailPage';
import { TeamListPage } from '../features/teams/TeamListPage';
import { TaskDetailPage } from '../features/tasks/TaskDetailPage';
import { TaskCreatePage } from '../features/tasks/TaskCreatePage';
import { TaskPage } from '../features/tasks/TaskPage';
import { ScheduledTaskPage } from '../features/tasks/ScheduledTaskPage';
import { WorkerDetailPage } from '../features/workers/WorkerDetailPage';
import { WorkerListPage } from '../features/workers/WorkerListPage';
import { ConversationPage } from '../features/conversations/ConversationPage';
import { ConversationListPage } from '../features/conversations/ConversationListPage';
import { ConsoleEntryPage } from '../features/projects/ConsoleEntryPage';
import { ConsoleLayout } from './AppShell';
import { AppShell } from './AppShell';
import { ManagementIdentityPage } from '../features/management/ManagementIdentityPage';
import { ManagementTemplatePage } from '../features/management/ManagementTemplatePage';
import { ManagementAgentSpecPage } from '../features/management/ManagementAgentSpecPage';
import { ManagementOrganizationPage } from '../features/management/ManagementOrganizationPage';
import { ManagementMcpPage } from '../features/management/ManagementMcpPage';
import { ManagementModelPage } from '../features/management/ManagementModelPage';
import { ManagementSkillPage } from '../features/management/ManagementSkillPage';
import { ManagementIntegrationPage } from '../features/management/ManagementIntegrationPage';
import { ManagementRolePage } from '../features/management/ManagementRolePage';
import { ManagementProjectPage } from '../features/projects/ManagementProjectPage';
import { ManagementArtifactPage } from '../features/management/ManagementArtifactPage';
import { ManagementUsagePage } from '../features/management/ManagementUsagePage';
import { ManagementBudgetPage } from '../features/management/ManagementBudgetPage';
import { ManagementAlertPage } from '../features/management/ManagementAlertPage';
import { ManagementAuditPage } from '../features/management/ManagementAuditPage';
import { ManagementMemoryPage } from '../features/management/ManagementMemoryPage';
import { ManagementSandboxPage } from '../features/management/ManagementSandboxPage';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 15_000 } },
});
export function AppRouter() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<AppShell />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route
              path="/console"
              element={
                <RequireAuth>
                  <ConsoleEntryPage />
                </RequireAuth>
              }
            />
            <Route
              path="/settings/identity"
              element={
                <RequireAuth>
                  <ManagementIdentityPage />
                </RequireAuth>
              }
            />
            <Route
              path="/settings/organizations"
              element={
                <RequireAuth>
                  <ManagementOrganizationPage />
                </RequireAuth>
              }
            />
            <Route
              path="/settings/integrations"
              element={
                <RequireAuth>
                  <ManagementIntegrationPage />
                </RequireAuth>
              }
            />
            <Route
              path="/settings/roles"
              element={
                <RequireAuth>
                  <ManagementRolePage />
                </RequireAuth>
              }
            />
            <Route
              path="/settings/projects"
              element={
                <RequireAuth>
                  <ManagementProjectPage />
                </RequireAuth>
              }
            />
            <Route path="/:projectId/*" element={<ProtectedProjectRoutes />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

function ProtectedProjectRoutes() {
  const { projectId = '' } = useParams();
  return (
    <RequireAuth>
      <ProjectProvider projectId={projectId}>
        <Routes>
          <Route element={<ConsoleLayout />}>
            <Route index element={<Navigate to="overview" replace />} />
            <Route path="overview" element={<OverviewPage projectId={projectId} />} />
            <Route path="teams" element={<TeamListPage projectId={projectId} />} />
            <Route path="teams/new" element={<TeamCreatePage projectId={projectId} />} />
            <Route path="teams/:teamId" element={<TeamDetailRoute projectId={projectId} />} />
            <Route path="tasks" element={<TaskPage projectId={projectId} />} />
            <Route path="scheduled-tasks" element={<ScheduledTaskPage projectId={projectId} />} />
            <Route path="tasks/new" element={<TaskCreatePage projectId={projectId} />} />
            <Route path="tasks/:taskId" element={<TaskDetailRoute projectId={projectId} />} />
            <Route path="workers" element={<WorkerListPage projectId={projectId} />} />
            <Route path="workers/:workerId" element={<WorkerDetailRoute projectId={projectId} />} />
            <Route path="templates" element={<ManagementTemplatePage projectId={projectId} />} />
            <Route path="agentspecs" element={<ManagementAgentSpecPage projectId={projectId} />} />
            <Route path="models" element={<ManagementModelPage />} />
            <Route path="mcp" element={<ManagementMcpPage />} />
            <Route path="skills" element={<ManagementSkillPage />} />
            <Route path="usage" element={<ManagementUsagePage projectId={projectId} />} />
            <Route path="budgets" element={<ManagementBudgetPage projectId={projectId} />} />
            <Route path="alerts" element={<ManagementAlertPage projectId={projectId} />} />
            <Route path="audit" element={<ManagementAuditPage projectId={projectId} />} />
            <Route path="memory" element={<ManagementMemoryPage projectId={projectId} />} />
            <Route path="sandboxes" element={<ManagementSandboxPage projectId={projectId} />} />
            <Route path="artifacts" element={<ManagementArtifactPage projectId={projectId} />} />
            <Route path="conversations" element={<ConversationListPage projectId={projectId} />} />
            <Route path="conversations/new" element={<ConversationPage projectId={projectId} />} />
            <Route
              path="conversations/:conversationId"
              element={<ConversationDetailRoute projectId={projectId} />}
            />
          </Route>
        </Routes>
      </ProjectProvider>
    </RequireAuth>
  );
}
function TeamDetailRoute({ projectId }: { projectId: string }) {
  const { teamId = '' } = useParams();
  return <TeamDetailPage projectId={projectId} teamId={teamId} />;
}
function TaskDetailRoute({ projectId }: { projectId: string }) {
  const { taskId = '' } = useParams();
  return <TaskDetailPage projectId={projectId} taskId={taskId} />;
}
function WorkerDetailRoute({ projectId }: { projectId: string }) {
  const { workerId = '' } = useParams();
  return <WorkerDetailPage projectId={projectId} workerId={workerId} />;
}
function ConversationDetailRoute({ projectId }: { projectId: string }) {
  const { conversationId = '' } = useParams();
  return <ConversationPage projectId={projectId} conversationId={conversationId} />;
}

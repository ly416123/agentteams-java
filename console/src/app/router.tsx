import { lazy, Suspense } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthProvider';
import { ProjectProvider } from '../auth/ProjectContext';
import { RequireAuth } from '../auth/RequireAuth';
import { AuthCallbackPage } from '../features/login/AuthCallbackPage';
import { LoginPage } from '../features/login/LoginPage';
import { ConsoleLayout } from './AppShell';
import { AppShell } from './AppShell';
import { RequirePermission } from '../auth/RequirePermission';
import { AuthorizationProvider } from '../auth/AuthorizationProvider';
import { managementRoutePermissions } from '../auth/permissions';

const ConsoleEntryPage = lazy(() =>
  import('../features/projects/ConsoleEntryPage').then((module) => ({
    default: module.ConsoleEntryPage,
  })),
);
const OverviewPage = lazy(() =>
  import('../features/overview/OverviewPage').then((module) => ({ default: module.OverviewPage })),
);
const TeamCreatePage = lazy(() =>
  import('../features/teams/TeamCreatePage').then((module) => ({ default: module.TeamCreatePage })),
);
const TeamDetailPage = lazy(() =>
  import('../features/teams/TeamDetailPage').then((module) => ({ default: module.TeamDetailPage })),
);
const TeamListPage = lazy(() =>
  import('../features/teams/TeamListPage').then((module) => ({ default: module.TeamListPage })),
);
const TaskDetailPage = lazy(() =>
  import('../features/tasks/TaskDetailPage').then((module) => ({ default: module.TaskDetailPage })),
);
const TaskCreatePage = lazy(() =>
  import('../features/tasks/TaskCreatePage').then((module) => ({ default: module.TaskCreatePage })),
);
const TaskPage = lazy(() =>
  import('../features/tasks/TaskPage').then((module) => ({ default: module.TaskPage })),
);
const ScheduledTaskPage = lazy(() =>
  import('../features/tasks/ScheduledTaskPage').then((module) => ({
    default: module.ScheduledTaskPage,
  })),
);
const WorkerDetailPage = lazy(() =>
  import('../features/workers/WorkerDetailPage').then((module) => ({
    default: module.WorkerDetailPage,
  })),
);
const WorkerListPage = lazy(() =>
  import('../features/workers/WorkerListPage').then((module) => ({
    default: module.WorkerListPage,
  })),
);
const ConversationPage = lazy(() =>
  import('../features/conversations/ConversationPage').then((module) => ({
    default: module.ConversationPage,
  })),
);
const ConversationListPage = lazy(() =>
  import('../features/conversations/ConversationListPage').then((module) => ({
    default: module.ConversationListPage,
  })),
);
const ManagementIdentityPage = lazy(() =>
  import('../features/management/ManagementIdentityPage').then((module) => ({
    default: module.ManagementIdentityPage,
  })),
);
const ManagementTemplatePage = lazy(() =>
  import('../features/management/ManagementTemplatePage').then((module) => ({
    default: module.ManagementTemplatePage,
  })),
);
const ManagementAgentSpecPage = lazy(() =>
  import('../features/management/ManagementAgentSpecPage').then((module) => ({
    default: module.ManagementAgentSpecPage,
  })),
);
const ManagementOrganizationPage = lazy(() =>
  import('../features/management/ManagementOrganizationPage').then((module) => ({
    default: module.ManagementOrganizationPage,
  })),
);
const ManagementMcpPage = lazy(() =>
  import('../features/management/ManagementMcpPage').then((module) => ({
    default: module.ManagementMcpPage,
  })),
);
const ManagementModelPage = lazy(() =>
  import('../features/management/ManagementModelPage').then((module) => ({
    default: module.ManagementModelPage,
  })),
);
const ManagementSkillPage = lazy(() =>
  import('../features/management/ManagementSkillPage').then((module) => ({
    default: module.ManagementSkillPage,
  })),
);
const ManagementIntegrationPage = lazy(() =>
  import('../features/management/ManagementIntegrationPage').then((module) => ({
    default: module.ManagementIntegrationPage,
  })),
);
const ManagementRolePage = lazy(() =>
  import('../features/management/ManagementRolePage').then((module) => ({
    default: module.ManagementRolePage,
  })),
);
const ManagementProjectPage = lazy(() =>
  import('../features/projects/ManagementProjectPage').then((module) => ({
    default: module.ManagementProjectPage,
  })),
);
const ManagementArtifactPage = lazy(() =>
  import('../features/management/ManagementArtifactPage').then((module) => ({
    default: module.ManagementArtifactPage,
  })),
);
const ManagementUsagePage = lazy(() =>
  import('../features/management/ManagementUsagePage').then((module) => ({
    default: module.ManagementUsagePage,
  })),
);
const ManagementBudgetPage = lazy(() =>
  import('../features/management/ManagementBudgetPage').then((module) => ({
    default: module.ManagementBudgetPage,
  })),
);
const ManagementAlertPage = lazy(() =>
  import('../features/management/ManagementAlertPage').then((module) => ({
    default: module.ManagementAlertPage,
  })),
);
const ManagementAuditPage = lazy(() =>
  import('../features/management/ManagementAuditPage').then((module) => ({
    default: module.ManagementAuditPage,
  })),
);
const ManagementMemoryPage = lazy(() =>
  import('../features/management/ManagementMemoryPage').then((module) => ({
    default: module.ManagementMemoryPage,
  })),
);
const ManagementSandboxPage = lazy(() =>
  import('../features/management/ManagementSandboxPage').then((module) => ({
    default: module.ManagementSandboxPage,
  })),
);
const ManagementEvaluationPage = lazy(() =>
  import('../features/management/ManagementEvaluationPage').then((module) => ({
    default: module.ManagementEvaluationPage,
  })),
);
const ArtifactQualityLedgerPage = lazy(() =>
  import('../features/quality/ArtifactQualityLedgerPage').then((module) => ({
    default: module.ArtifactQualityLedgerPage,
  })),
);

function RouteLoading() {
  return (
    <div className="panel loading-block" role="status" aria-live="polite">
      正在加载页面…
    </div>
  );
}

function withRouteLoading(element: React.ReactNode) {
  return <Suspense fallback={<RouteLoading />}>{element}</Suspense>;
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 15_000 } },
});
export function AppRouter() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <AuthorizationProvider>
            <Routes>
              <Route path="/" element={<AppShell />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/auth/callback" element={<AuthCallbackPage />} />
              <Route
                path="/console"
                element={<RequireAuth>{withRouteLoading(<ConsoleEntryPage />)}</RequireAuth>}
              />
              <Route
                path="/settings"
                element={
                  <RequireAuth>
                    <ProjectProvider>
                      <ConsoleLayout />
                    </ProjectProvider>
                  </RequireAuth>
                }
              >
                {/* 管理页挂在 ConsoleLayout 下，保证侧边栏在设置空间持续可见；ProjectProvider 供顶栏 ProjectSwitcher 使用。 */}
                <Route
                  path="identity"
                  element={
                    <RequirePermission permission={managementRoutePermissions.identity}>
                      {withRouteLoading(<ManagementIdentityPage />)}
                    </RequirePermission>
                  }
                />
                <Route
                  path="organizations"
                  element={
                    <RequirePermission permission={managementRoutePermissions.organizations}>
                      {withRouteLoading(<ManagementOrganizationPage />)}
                    </RequirePermission>
                  }
                />
                <Route
                  path="integrations"
                  element={
                    <RequirePermission permission={managementRoutePermissions.integrations}>
                      {withRouteLoading(<ManagementIntegrationPage />)}
                    </RequirePermission>
                  }
                />
                <Route
                  path="roles"
                  element={
                    <RequirePermission permission={managementRoutePermissions.roles}>
                      {withRouteLoading(<ManagementRolePage />)}
                    </RequirePermission>
                  }
                />
                <Route
                  path="projects"
                  element={
                    <RequirePermission permission={managementRoutePermissions.projects}>
                      {withRouteLoading(<ManagementProjectPage />)}
                    </RequirePermission>
                  }
                />
              </Route>
              <Route path="/:projectId/*" element={<ProtectedProjectRoutes />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </AuthorizationProvider>
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
            <Route
              path="overview"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<OverviewPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="teams"
              element={
                <RequirePermission permission="team:read">
                  {withRouteLoading(<TeamListPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="teams/new"
              element={
                <RequirePermission permission="team:write">
                  {withRouteLoading(<TeamCreatePage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="teams/:teamId"
              element={
                <RequirePermission permission="team:read">
                  {withRouteLoading(<TeamDetailRoute projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="tasks"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<TaskPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="scheduled-tasks"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<ScheduledTaskPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="tasks/new"
              element={
                <RequirePermission permission="task:create">
                  {withRouteLoading(<TaskCreatePage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="tasks/:taskId"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<TaskDetailRoute projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="workers"
              element={
                <RequirePermission permission="agent:read">
                  {withRouteLoading(<WorkerListPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="workers/:workerId"
              element={
                <RequirePermission permission="agent:read">
                  {withRouteLoading(<WorkerDetailRoute projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="templates"
              element={
                <RequirePermission permission={managementRoutePermissions.templates}>
                  {withRouteLoading(<ManagementTemplatePage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="agentspecs"
              element={
                <RequirePermission permission={managementRoutePermissions.agentspecs}>
                  {withRouteLoading(<ManagementAgentSpecPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="models"
              element={
                <RequirePermission permission={managementRoutePermissions.models}>
                  {withRouteLoading(<ManagementModelPage />)}
                </RequirePermission>
              }
            />
            <Route
              path="mcp"
              element={
                <RequirePermission permission={managementRoutePermissions.mcp}>
                  {withRouteLoading(<ManagementMcpPage />)}
                </RequirePermission>
              }
            />
            <Route
              path="skills"
              element={
                <RequirePermission permission={managementRoutePermissions.skills}>
                  {withRouteLoading(<ManagementSkillPage />)}
                </RequirePermission>
              }
            />
            <Route
              path="usage"
              element={
                <RequirePermission permission={managementRoutePermissions.usage}>
                  {withRouteLoading(<ManagementUsagePage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="budgets"
              element={
                <RequirePermission permission={managementRoutePermissions.budgets}>
                  {withRouteLoading(<ManagementBudgetPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="alerts"
              element={
                <RequirePermission permission={managementRoutePermissions.alerts}>
                  {withRouteLoading(<ManagementAlertPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="audit"
              element={
                <RequirePermission permission={managementRoutePermissions.audit}>
                  {withRouteLoading(<ManagementAuditPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="memory"
              element={
                <RequirePermission permission={managementRoutePermissions.memory}>
                  {withRouteLoading(<ManagementMemoryPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="sandboxes"
              element={
                <RequirePermission permission={managementRoutePermissions.sandboxes}>
                  {withRouteLoading(<ManagementSandboxPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="artifacts"
              element={
                <RequirePermission permission={managementRoutePermissions.artifacts}>
                  {withRouteLoading(<ManagementArtifactPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="evaluations"
              element={
                <RequirePermission permission={managementRoutePermissions.evaluations}>
                  {withRouteLoading(<ManagementEvaluationPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="evaluations/:runId"
              element={
                <RequirePermission permission={managementRoutePermissions.evaluations}>
                  {withRouteLoading(<EvaluationDetailRoute projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="quality"
              element={
                <RequirePermission permission={managementRoutePermissions.quality}>
                  {withRouteLoading(<ArtifactQualityLedgerPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="conversations"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<ConversationListPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="conversations/new"
              element={
                <RequirePermission permission="task:create">
                  {withRouteLoading(<ConversationPage projectId={projectId} />)}
                </RequirePermission>
              }
            />
            <Route
              path="conversations/:conversationId"
              element={
                <RequirePermission permission="task:read">
                  {withRouteLoading(<ConversationDetailRoute projectId={projectId} />)}
                </RequirePermission>
              }
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
function EvaluationDetailRoute({ projectId }: { projectId: string }) {
  const { runId = '' } = useParams();
  return <ManagementEvaluationPage projectId={projectId} runId={runId} />;
}

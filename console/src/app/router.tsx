import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Navigate, Route, Routes, useParams } from 'react-router-dom';
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
import { WorkerDetailPage } from '../features/workers/WorkerDetailPage';
import { WorkerListPage } from '../features/workers/WorkerListPage';
import { ConsoleLayout } from './AppShell';
import { AppShell } from './AppShell';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 15_000 } },
});
export function AppRouter() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<AppShell />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="/:projectId/*" element={<ProtectedProjectRoutes />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
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
            <Route path="tasks/new" element={<TaskCreatePage projectId={projectId} />} />
            <Route path="tasks/:taskId" element={<TaskDetailRoute projectId={projectId} />} />
            <Route path="workers" element={<WorkerListPage projectId={projectId} />} />
            <Route path="workers/:workerId" element={<WorkerDetailRoute projectId={projectId} />} />
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

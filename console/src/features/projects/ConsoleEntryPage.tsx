import { useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import { listProjects } from '../../api/projects';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { queryKeys } from '../../queries/queryKeys';

export function ConsoleEntryPage() {
  const projects = useQuery({
    queryKey: queryKeys.projects,
    queryFn: () => listProjects(),
  });

  if (projects.isLoading) {
    return <div className="loading-screen">正在加载可用 Project…</div>;
  }

  if (projects.isError) {
    return <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />;
  }

  const firstProject = projects.data?.items[0];
  if (!firstProject) {
    return (
      <div className="public-main">
        <EmptyState
          title="暂无可访问的 Project"
          description="请联系组织管理员授予 Project 访问权限。"
        />
      </div>
    );
  }

  return <Navigate to={`/${firstProject.id}/overview`} replace />;
}

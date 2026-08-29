import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listProjects } from '../../api/projects';
import { useProject } from '../../auth/ProjectContext';
import { queryKeys } from '../../queries/queryKeys';
import { ErrorState } from '../../components/ErrorState';

export function ProjectSwitcher() {
  const { projectId, setProjectId } = useProject();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const projects = useQuery({ queryKey: queryKeys.projects, queryFn: () => listProjects() });
  const handleChange = (nextProjectId: string) => {
    if (!nextProjectId || nextProjectId === projectId) return;
    queryClient.removeQueries({ predicate: ({ queryKey }) => queryKey.includes(projectId) });
    setProjectId(nextProjectId);
    navigate(`/${nextProjectId}/overview`);
  };

  if (projects.isError) {
    return <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />;
  }

  return (
    <label className="project-switcher">
      <span>Project</span>
      <select
        value={projectId || ''}
        onChange={(event) => handleChange(event.target.value)}
        aria-label="当前 Project"
      >
        {!projects.data && <option value="">加载中…</option>}
        {projects.data?.items.map((project) => (
          <option key={project.id} value={project.id}>
            {project.name}
          </option>
        ))}
      </select>
    </label>
  );
}

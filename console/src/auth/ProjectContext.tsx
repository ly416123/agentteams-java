import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

type ProjectContextValue = { projectId?: string; setProjectId: (projectId: string) => void };
const ProjectContext = createContext<ProjectContextValue | null>(null);
const SELECTED_PROJECT_STORAGE_KEY = 'agentteams.selectedProjectId';

export function ProjectProvider({
  projectId: initialProjectId,
  children,
}: {
  projectId?: string;
  children: ReactNode;
}) {
  const [projectId, setProjectId] = useState(
    () =>
      initialProjectId || window.localStorage.getItem(SELECTED_PROJECT_STORAGE_KEY) || undefined,
  );
  useEffect(() => {
    if (initialProjectId)
      setProjectId((current) => (current === initialProjectId ? current : initialProjectId));
  }, [initialProjectId]);
  useEffect(() => {
    if (projectId) window.localStorage.setItem(SELECTED_PROJECT_STORAGE_KEY, projectId);
  }, [projectId]);
  const value = useMemo(() => ({ projectId, setProjectId }), [projectId]);
  return <ProjectContext.Provider value={value}>{children}</ProjectContext.Provider>;
}

export function useProject() {
  const value = useContext(ProjectContext);
  if (!value) throw new Error('useProject must be used within ProjectProvider');
  return value;
}

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

type ProjectContextValue = { projectId?: string; setProjectId: (projectId: string) => void };
const ProjectContext = createContext<ProjectContextValue | null>(null);

export function ProjectProvider({
  projectId: initialProjectId,
  children,
}: {
  projectId?: string;
  children: ReactNode;
}) {
  const [projectId, setProjectId] = useState(initialProjectId);
  const value = useMemo(() => ({ projectId, setProjectId }), [projectId]);
  return <ProjectContext.Provider value={value}>{children}</ProjectContext.Provider>;
}

export function useProject() {
  const value = useContext(ProjectContext);
  if (!value) throw new Error('useProject must be used within ProjectProvider');
  return value;
}

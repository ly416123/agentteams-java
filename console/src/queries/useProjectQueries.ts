import { useQuery } from '@tanstack/react-query';
import { listProjects } from '../api/projects';
import { normalizeCursorPage } from '../api/types';
import { queryKeys } from './queryKeys';
export function useProjects() {
  return useQuery({
    queryKey: queryKeys.projects,
    queryFn: () => listProjects(),
    select: normalizeCursorPage,
  });
}

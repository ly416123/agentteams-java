import { apiClient, type HttpClient } from './httpClient';
import type { Project } from './types';

export function listProjects(client: HttpClient = apiClient) {
  return client.request<Project[]>('/api/v1/projects');
}

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { TASK_PHASES, TaskBoard } from '../../src/features/tasks/TaskBoard';
import { labelStatus } from '../../src/i18n/labels';

describe('TaskBoard', () => {
  it('renders every TaskPhase column from the domain', () => {
    render(
      <MemoryRouter>
        <TaskBoard projectId="p-1" tasks={[]} />
      </MemoryRouter>,
    );

    for (const phase of TASK_PHASES) {
      expect(screen.getByRole('region', { name: labelStatus(phase) })).toBeInTheDocument();
    }
  });
});

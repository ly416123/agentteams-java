import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { TASK_PHASES, TaskBoard } from '../../src/features/tasks/TaskBoard';

describe('TaskBoard', () => {
  it('renders every TaskPhase column from the domain', () => {
    render(
      <MemoryRouter>
        <TaskBoard projectId="p-1" tasks={[]} />
      </MemoryRouter>,
    );

    for (const phase of TASK_PHASES) {
      expect(screen.getByRole('region', { name: phase })).toBeInTheDocument();
    }
  });
});

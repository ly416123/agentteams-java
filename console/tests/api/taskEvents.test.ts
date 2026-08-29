import { describe, expect, it } from 'vitest';
import { parseTaskEventStream } from '../../src/api/taskEvents';

describe('task event parser', () => {
  it('parses SSE data frames without relying on response.json', () => {
    const events = parseTaskEventStream(
      'id: e-1\nevent: task.updated\ndata: {"message":"已排队"}\n\n',
    );

    expect(events).toEqual([
      {
        id: 'e-1',
        type: 'task.updated',
        message: '已排队',
      },
    ]);
  });

  it('also accepts the JSON history representation', () => {
    expect(
      parseTaskEventStream(
        JSON.stringify([{ id: 'e-2', type: 'task.created', message: '已创建' }]),
      ),
    ).toEqual([{ id: 'e-2', type: 'task.created', message: '已创建' }]);
  });
});

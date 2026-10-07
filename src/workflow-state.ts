export type WorkflowStatus =
  | 'draft'
  | 'pending'
  | 'in_progress'
  | 'returned'
  | 'completed'
  | 'closed';

export type WorkflowAction =
  | 'submit'
  | 'accept'
  | 'return'
  | 'transfer'
  | 'complete'
  | 'close';

export interface WorkflowItem {
  id: string;
  status: WorkflowStatus;
  ownerId: string;
  version: number;
  dueAt?: string;
}

const transitions: Record<WorkflowStatus, Partial<Record<WorkflowAction, WorkflowStatus>>> = {
  draft: { submit: 'pending' },
  pending: { accept: 'in_progress', return: 'returned', transfer: 'pending' },
  in_progress: { return: 'returned', complete: 'completed', transfer: 'pending' },
  returned: { submit: 'pending' },
  completed: { close: 'closed' },
  closed: {},
};

export function nextStatus(status: WorkflowStatus, action: WorkflowAction): WorkflowStatus {
  const next = transitions[status][action];
  if (!next) throw new Error(`Action ${action} is not allowed from ${status}`);
  return next;
}

// This pure example is a domain guard, not an authentication boundary. A server
// must resolve actorId from its session and authorize both current and next owners.
export function applyAction(
  item: WorkflowItem,
  action: WorkflowAction,
  actorId: string,
  expectedVersion: number,
  nextOwnerId?: string,
): WorkflowItem {
  if (!actorId.trim() || !item.ownerId.trim()) throw new Error('An actor and current owner are required.');
  if (!Number.isSafeInteger(item.version) || item.version < 0 || item.version === Number.MAX_SAFE_INTEGER) {
    throw new Error('The item version must be a non-negative, incrementable safe integer.');
  }
  if (item.version !== expectedVersion) throw new Error('The item changed. Refresh before trying again.');
  if (item.ownerId !== actorId) throw new Error('Only the current owner may perform this action.');
  const status = nextStatus(item.status, action);
  if (action === 'transfer') {
    if (!nextOwnerId?.trim() || nextOwnerId !== nextOwnerId.trim() || nextOwnerId === item.ownerId) {
      throw new Error('A different, non-empty next owner is required for a transfer.');
    }
  } else if (nextOwnerId !== undefined) {
    throw new Error('Only a transfer may change the owner.');
  }
  return {
    ...item,
    status,
    ownerId: action === 'transfer' ? nextOwnerId! : item.ownerId,
    version: item.version + 1,
  };
}

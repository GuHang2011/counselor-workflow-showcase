export interface ApiEnvelope<T> {
  code: number | string;
  data: T;
  message?: string;
}

export interface RequestContext {
  accessToken?: string;
  clientVersion: string;
  idempotencyKey?: string;
}

export function buildMutationContext(token: string, clientVersion: string, operationId: string): RequestContext {
  return {
    accessToken: token,
    clientVersion,
    idempotencyKey: operationId,
  };
}

export interface MutationRetryContract {
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  serverDeduplication: boolean;
}

// A key alone cannot make a write safe. The exact endpoint must explicitly
// guarantee server-side deduplication. Keep the same key AND payload per operation.
// This only checks eligibility: retry transient failures with bounded backoff;
// do not automatically retry validation, authorization, or version conflicts.
export function isRetryableMutation(
  method: string,
  path: string,
  context: RequestContext,
  contract?: MutationRetryContract,
): boolean {
  const normalizedMethod = method.toUpperCase();
  return Boolean(
    contract?.serverDeduplication === true
    && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(normalizedMethod)
    && contract.method === normalizedMethod
    && contract.path === path
    && path.startsWith('/')
    && context.idempotencyKey?.trim(),
  );
}

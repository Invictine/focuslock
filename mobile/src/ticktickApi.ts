import { makeFunctionReference } from 'convex/server';

export type TickTickStatus = {
  connected: boolean;
  revision: number;
  serverConfigured: boolean;
  userName?: string;
};

export type TickTickTask = {
  id: string;
  projectId: string;
  title: string;
  projectName: string;
};

export type TickTickFocusImport = {
  found: number;
  imported: number;
  earnedMinutes: number;
};

export const tickTickStatusRef = makeFunctionReference<'action', Record<string, never>, TickTickStatus>('ticktickIos:status');
export const tickTickAuthorizeRef = makeFunctionReference<'action', Record<string, never>, { url: string }>('ticktickIos:authorize');
export const tickTickConnectRef = makeFunctionReference<'action', { callback: string }, { connected: true }>('ticktickIos:connect');
export const tickTickTasksRef = makeFunctionReference<'action', Record<string, never>, TickTickTask[]>('ticktickIos:tasks');
export const tickTickCompleteTaskRef = makeFunctionReference<'action', { taskId: string; projectId: string }, { completed: true }>('ticktickIos:completeTask');
export const tickTickSyncFocusRef = makeFunctionReference<'action', { timeZone: string }, TickTickFocusImport>('ticktickIos:syncFocus');

export const tickTickSaveConnectionRef = makeFunctionReference<
  'mutation',
  { expectedRevision: number; connection: null },
  { applied: boolean; revision: number }
>('ticktick:saveConnection');

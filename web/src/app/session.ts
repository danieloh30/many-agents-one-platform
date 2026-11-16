import { create } from 'zustand';
import { api, ApiError } from '../api/client';
import { readStorage, writeStorage } from './storage';
import { useConfig } from '../api/queries';

const TOKEN_KEY = 'maop.presenterToken';

export type SessionState = 'none' | 'checking' | 'valid' | 'invalid' | 'unverified';

interface Session {
  token: string | null;
  state: SessionState;
  /** Validates with GET /api/v1/session and stores the token in sessionStorage when valid. */
  signIn(token: string): Promise<boolean>;
  signOut(): void;
  /** Re-validates a token restored from sessionStorage. */
  revalidate(): Promise<void>;
  /** Called when a mutation returned 401. */
  markRejected(): void;
}

export const useSession = create<Session>((set, get) => ({
  token: readStorage('session', TOKEN_KEY),
  state: readStorage('session', TOKEN_KEY) ? 'checking' : 'none',
  signIn: async (token) => {
    const t = token.trim();
    if (!t) return false;
    set({ state: 'checking' });
    try {
      await api.session(t);
      writeStorage('session', TOKEN_KEY, t);
      set({ token: t, state: 'valid' });
      return true;
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        set({ state: get().token ? get().state : 'invalid' });
        throw e;
      }
      set({ state: get().token ? 'unverified' : 'none' });
      throw e;
    }
  },
  signOut: () => {
    writeStorage('session', TOKEN_KEY, null);
    set({ token: null, state: 'none' });
  },
  revalidate: async () => {
    const t = get().token;
    if (!t) return;
    try {
      await api.session(t);
      set({ state: 'valid' });
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        writeStorage('session', TOKEN_KEY, null);
        set({ token: null, state: 'invalid' });
      } else {
        set({ state: 'unverified' });
      }
    }
  },
  markRejected: () => {
    writeStorage('session', TOKEN_KEY, null);
    set({ token: null, state: 'invalid' });
  },
}));

/** Mutation controls are enabled only with a token the server accepted. */
export function useCanMutate(): { canMutate: boolean; token: string | null; reason: string | null } {
  const token = useSession((s) => s.token);
  const state = useSession((s) => s.state);
  const required = usePresenterTokenRequired();
  if (!required) return { canMutate: true, token: null, reason: null };
  if (token && state === 'valid') return { canMutate: true, token, reason: null };
  if (token && state === 'checking') return { canMutate: false, token: null, reason: 'Checking presenter token…' };
  if (token && state === 'unverified')
    return { canMutate: false, token: null, reason: 'Presenter token could not be verified (API unreachable).' };
  return {
    canMutate: false,
    token: null,
    reason: 'Read-only: enter the presenter token to run scenarios and approve actions.',
  };
}

/** False only when the backend says mutations are open (MAOP_PRESENTER_AUTH=false). Unknown means required. */
export function usePresenterTokenRequired(): boolean {
  const config = useConfig();
  return config.data?.auth?.presenterTokenRequired !== false;
}

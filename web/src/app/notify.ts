import { create } from 'zustand';

export type ToastTone = 'info' | 'success' | 'policy' | 'error';

export interface ToastItem {
  id: number;
  tone: ToastTone;
  title: string;
  description?: string;
}

interface NotifyState {
  toasts: ToastItem[];
  /** Latest polite announcement for the aria-live region. */
  announcement: { id: number; text: string } | null;
  toast(t: Omit<ToastItem, 'id'>): void;
  dismiss(id: number): void;
  announce(text: string): void;
}

let nextId = 1;

export const useNotify = create<NotifyState>((set) => ({
  toasts: [],
  announcement: null,
  toast: (t) => set((s) => ({ toasts: [...s.toasts.slice(-4), { ...t, id: nextId++ }] })),
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  announce: (text) => set({ announcement: { id: nextId++, text } }),
}));

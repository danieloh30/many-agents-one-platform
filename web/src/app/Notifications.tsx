import { Toast } from 'radix-ui';
import { Ban, CircleCheck, CircleX, Info, X } from 'lucide-react';
import { useNotify, type ToastTone } from './notify';

const toneIcon: Record<ToastTone, typeof Info> = { info: Info, success: CircleCheck, policy: Ban, error: CircleX };
const toneClass: Record<ToastTone, string> = {
  info: 'border-primary/50',
  success: 'border-teal/60',
  policy: 'border-danger/60',
  error: 'border-danger',
};
const iconClass: Record<ToastTone, string> = { info: 'text-primary', success: 'text-teal', policy: 'text-danger', error: 'text-danger' };

/** Non-blocking toasts. Policy denials are informational, not application errors. */
export function Toaster() {
  const toasts = useNotify((s) => s.toasts);
  const dismiss = useNotify((s) => s.dismiss);
  return (
    <>
      {toasts.map((t) => {
        const Icon = toneIcon[t.tone];
        return (
          <Toast.Root
            key={t.id}
            type="background"
            duration={6000}
            onOpenChange={(o) => !o && dismiss(t.id)}
            className={`flex items-start gap-2 rounded-lg border-l-4 border bg-surface p-3 text-text shadow-xl ${toneClass[t.tone]}`}
          >
            <Icon aria-hidden size={18} className={`mt-0.5 shrink-0 ${iconClass[t.tone]}`} />
            <div className="min-w-0 flex-1">
              <Toast.Title className="text-sm font-semibold">{t.title}</Toast.Title>
              {t.description ? <Toast.Description className="mt-0.5 text-sm text-text-muted">{t.description}</Toast.Description> : null}
            </div>
            <Toast.Close aria-label="Dismiss" className="rounded p-0.5 text-text-muted hover:text-text">
              <X aria-hidden size={14} />
            </Toast.Close>
          </Toast.Root>
        );
      })}
      <Toast.Viewport className="fixed right-3 bottom-3 z-[60] flex w-[min(92vw,24rem)] flex-col gap-2 outline-none" />
    </>
  );
}

/** Polite announcements of state changes and policy decisions only. */
export function LiveRegion() {
  const a = useNotify((s) => s.announcement);
  return (
    <div aria-live="polite" aria-atomic="true" className="sr-only" data-testid="live-region">
      {a ? <span key={a.id}>{a.text}</span> : null}
    </div>
  );
}

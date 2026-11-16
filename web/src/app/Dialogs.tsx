import { useState, type FormEvent } from 'react';
import { Dialog } from 'radix-ui';
import { KeyRound, LogOut, X } from 'lucide-react';
import { describeError, ApiError } from '../api/client';
import { Button } from '../design/ui';
import { useSession } from './session';
import { SHORTCUTS } from './shortcuts';
import { useUi } from './uiStore';

function Shell({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean;
  onOpenChange(o: boolean): void;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(94vw,28rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-surface p-5 text-text shadow-2xl">
          <div className="flex items-center justify-between">
            <Dialog.Title className="text-lg font-semibold">{title}</Dialog.Title>
            <Dialog.Close asChild>
              <Button size="sm" variant="ghost" icon={X} aria-label="Close" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-1 text-sm text-text-muted">{description}</Dialog.Description>
          <div className="mt-4">{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function TokenDialog() {
  const open = useUi((s) => s.tokenDialogOpen);
  const setOpen = useUi((s) => s.setTokenDialogOpen);
  const state = useSession((s) => s.state);
  const signIn = useSession((s) => s.signIn);
  const signOut = useSession((s) => s.signOut);
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await signIn(value);
      setValue('');
      setOpen(false);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'Token rejected by the console API.' : describeError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell
      open={open}
      onOpenChange={setOpen}
      title="Presenter token"
      description="Required to run scenarios, approve actions, and reset the demonstration. Kept for this browser tab only."
    >
      {state === 'valid' ? (
        <div className="space-y-3 text-sm">
          <p>A valid presenter token is active for this tab.</p>
          <Button
            icon={LogOut}
            onClick={() => {
              signOut();
              setOpen(false);
            }}
          >
            Forget token
          </Button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-3">
          <label className="block text-sm">
            Token
            <input
              type="password"
              autoComplete="off"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              className="mt-1 w-full rounded-md border border-border bg-surface px-2 py-1.5 font-mono text-sm text-text"
              aria-invalid={!!error}
              aria-describedby={error ? 'token-error' : undefined}
            />
          </label>
          {error ? (
            <p id="token-error" role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : null}
          <div className="flex justify-end">
            <Button type="submit" variant="primary" icon={KeyRound} disabled={busy || !value.trim()}>
              {busy ? 'Checking…' : 'Validate and use'}
            </Button>
          </div>
        </form>
      )}
    </Shell>
  );
}

export function HelpDialog() {
  const open = useUi((s) => s.helpOpen);
  const setOpen = useUi((s) => s.setHelpOpen);
  return (
    <Shell open={open} onOpenChange={setOpen} title="Keyboard shortcuts" description="Shortcuts are ignored while typing in a field.">
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5 text-sm">
        {SHORTCUTS.map(([k, d]) => (
          <div key={k} className="contents">
            <dt>
              <kbd className="rounded border border-border bg-surface-raised px-1.5 py-0.5 font-mono">{k}</kbd>
            </dt>
            <dd>{d}</dd>
          </div>
        ))}
      </dl>
    </Shell>
  );
}

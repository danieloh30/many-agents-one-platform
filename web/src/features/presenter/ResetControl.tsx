import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { RotateCcw } from 'lucide-react';
import { api, ApiError, describeError } from '../../api/client';
import { useLiveStore } from '../../api/liveStore';
import { useNotify } from '../../app/notify';
import { useCanMutate, useSession } from '../../app/session';
import { useUi } from '../../app/uiStore';
import { ConfirmDialog } from '../../design/ConfirmDialog';
import { GatedButton } from '../../design/ui';

export function ResetControl() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { canMutate, token, reason } = useCanMutate();
  const toast = useNotify((s) => s.toast);
  const announce = useNotify((s) => s.announce);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const reset = async () => {
    if (!canMutate) return;
    setBusy(true);
    try {
      await api.reset(token);
      useLiveStore.getState().clearEvents();
      useUi.getState().resetDemoState();
      qc.removeQueries({ predicate: (q) => ['run', 'timeline', 'invocations', 'invocation'].includes(String(q.queryKey[0])) });
      void qc.invalidateQueries();
      navigate('/');
      announce('Demonstration state reset');
      setOpen(false);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) useSession.getState().markRejected();
      toast({ tone: 'error', title: 'Reset refused', description: describeError(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <GatedButton size="sm" variant="danger" icon={RotateCcw} disabledReason={reason} onClick={() => setOpen(true)}>
        Reset
      </GatedButton>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title="Reset demonstration state?"
        tone="danger"
        busy={busy}
        confirmLabel="Reset"
        onConfirm={() => void reset()}
        body={
          <>
            <p>Deletes runs, tool audit records, simulated actions, and queued messages.</p>
            <p>Platform components are not touched. Refused while a burst is active.</p>
          </>
        }
      />
    </>
  );
}

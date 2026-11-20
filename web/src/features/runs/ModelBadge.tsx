import { Brain, FlaskConical } from 'lucide-react';
import type { ModelInfo } from '../../api/types';
import { Pill } from '../../design/ui';

/** Quiet inline attribution on cards; deterministic mode stays amber and explicit. */
export function ModelBadge({ model, compact = false }: { model: ModelInfo | null | undefined; compact?: boolean }) {
  if (!model) return null;
  if (model.mode === 'DETERMINISTIC')
    return (
      <Pill tone="warn" icon={FlaskConical} testId="card-model-badge">
        DETERMINISTIC
      </Pill>
    );
  if (compact)
    return (
      <span className="inline-flex min-w-0 items-center gap-1 text-sm text-purple" data-testid="card-model-badge">
        <Brain aria-hidden size={14} className="shrink-0" />
        <span className="break-words">{model.name ? `Model: ${model.name}` : 'LIVE model'}</span>
      </span>
    );
  return (
    <Pill tone="purple" icon={Brain} testId="card-model-badge">
      {model.name ? `Model: ${model.name}` : 'LIVE model'}
    </Pill>
  );
}

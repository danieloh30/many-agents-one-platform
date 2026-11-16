import { Brain, FlaskConical } from 'lucide-react';
import type { ModelInfo } from '../../api/types';
import { Pill } from '../../design/ui';

/** Badge on every model-derived card; deterministic mode is amber and explicit. */
export function ModelBadge({ model }: { model: ModelInfo | null | undefined }) {
  if (!model) return null;
  if (model.mode === 'DETERMINISTIC')
    return (
      <Pill tone="warn" icon={FlaskConical} testId="card-model-badge">
        DETERMINISTIC
      </Pill>
    );
  return (
    <Pill tone="purple" icon={Brain} testId="card-model-badge">
      {model.name ? `Model: ${model.name}` : 'LIVE model'}
    </Pill>
  );
}

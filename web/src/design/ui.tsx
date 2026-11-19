import { useId, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Tooltip } from 'radix-ui';
import {
  Ban,
  Check,
  CircleCheck,
  CircleDashed,
  CircleQuestionMark,
  CircleX,
  Copy,
  RotateCcw,
  TrendingUp,
  TriangleExclamationPoint,
  type LucideIcon,
} from 'lucide-react';
import { middleTruncate, UNAVAILABLE } from './format';

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

// ---- Buttons -----------------------------------------------------------------------------

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

const variants: Record<Variant, string> = {
  primary: 'bg-primary text-on-primary border-primary hover:opacity-90',
  secondary: 'bg-surface text-text border-border hover:bg-surface-raised',
  ghost: 'bg-transparent text-text border-transparent hover:bg-surface-raised',
  danger: 'bg-surface text-danger border-danger hover:tint-danger',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: 'sm' | 'md';
  icon?: LucideIcon;
}

export function Button({ variant = 'secondary', size = 'md', icon: Icon, className, children, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      className={cx(
        'ui-transition inline-flex items-center justify-center gap-1.5 rounded-md border font-medium',
        'disabled:cursor-not-allowed disabled:opacity-55',
        size === 'sm' ? 'px-2 py-1 text-sm' : 'px-3 py-1.5 text-sm',
        variants[variant],
        className,
      )}
      {...rest}
    >
      {Icon ? <Icon aria-hidden size={16} /> : null}
      {children}
    </button>
  );
}

/**
 * A mutation control that stays visible when the presenter token is missing: it is disabled and the
 * reason is exposed both as a tooltip and to assistive technology.
 */
export function GatedButton({ disabledReason, ...props }: ButtonProps & { disabledReason: string | null }) {
  const id = useId();
  if (!disabledReason) return <Button {...props} />;
  return (
    <Tip content={disabledReason}>
      <span tabIndex={0} className="inline-flex rounded-md" aria-describedby={id}>
        <Button {...props} disabled aria-describedby={id} />
        <span id={id} className="sr-only">
          {disabledReason}
        </span>
      </span>
    </Tip>
  );
}

// ---- Tooltip -----------------------------------------------------------------------------

export function Tip({ content, children }: { content: ReactNode; children: ReactNode }) {
  return (
    <Tooltip.Root delayDuration={250}>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Tooltip.Portal>
        <Tooltip.Content
          sideOffset={6}
          className="z-50 max-w-xs rounded-md border border-border bg-surface-raised px-2.5 py-1.5 text-sm text-text shadow-lg"
        >
          {content}
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  );
}

// ---- Pills and badges ----------------------------------------------------------------------

export type Tone = 'ok' | 'warn' | 'danger' | 'info' | 'neutral' | 'purple' | 'live';

const toneClass: Record<Tone, string> = {
  ok: 'text-teal tint-teal border-teal/40',
  warn: 'text-warn tint-warn border-warn/50',
  danger: 'text-danger tint-danger border-danger/50',
  info: 'text-primary tint-primary border-primary/40',
  purple: 'text-purple tint-purple border-purple/40',
  neutral: 'text-text-muted bg-surface-raised border-border',
  live: 'text-text tint-accent border-accent/60',
};

export function Pill({
  tone = 'neutral',
  icon: Icon,
  children,
  className,
  title,
  testId,
}: {
  tone?: Tone;
  icon?: LucideIcon;
  children: ReactNode;
  className?: string;
  title?: string;
  testId?: string;
}) {
  return (
    <span
      title={title}
      data-testid={testId}
      data-tone={tone}
      className={cx(
        'inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-sm font-semibold leading-5',
        toneClass[tone],
        className,
      )}
    >
      {Icon ? <Icon aria-hidden size={14} className={tone === 'live' ? 'text-accent' : undefined} /> : null}
      {children}
    </span>
  );
}

/** Component status from the backend: always icon + text. Unknown values never render green. */
export function StatusPill({ status, label }: { status: string | null | undefined; label?: string }) {
  switch (status) {
    case 'OK':
      return (
        <Pill tone="ok" icon={CircleCheck}>
          {label ? `${label}: ` : ''}OK
        </Pill>
      );
    case 'SCALING':
      return (
        <Pill tone="info" icon={TrendingUp}>
          {label ? `${label}: ` : ''}Scaling up
        </Pill>
      );
    case 'DEGRADED':
      return (
        <Pill tone="warn" icon={TriangleExclamationPoint}>
          {label ? `${label}: ` : ''}Degraded
        </Pill>
      );
    case 'UNAVAILABLE':
      return (
        <Pill tone="danger" icon={CircleX}>
          {label ? `${label}: ` : ''}Unavailable
        </Pill>
      );
    default:
      return (
        <Pill tone="neutral" icon={CircleQuestionMark}>
          {label ? `${label}: ` : ''}Unknown
        </Pill>
      );
  }
}

export function DecisionPill({ status }: { status: string }) {
  if (status === 'DENIED')
    return (
      <Pill tone="danger" icon={Ban} testId="decision-pill">
        DENIED
      </Pill>
    );
  if (status === 'REPLAYED')
    return (
      <Pill tone="info" icon={RotateCcw} testId="decision-pill">
        REPLAYED
      </Pill>
    );
  if (status === 'EXECUTED' || status === 'ALLOWED')
    return (
      <Pill tone="ok" icon={CircleCheck} testId="decision-pill">
        ALLOWED
      </Pill>
    );
  return (
    <Pill tone="neutral" icon={CircleDashed} testId="decision-pill">
      {status}
    </Pill>
  );
}

/** Renders "Unavailable" (with optional reason) for null values; never zero. */
export function Value({
  value,
  reason,
  className,
}: {
  value: ReactNode | null | undefined;
  reason?: string;
  className?: string;
}) {
  if (value === null || value === undefined || value === UNAVAILABLE) {
    return (
      <span className={cx('text-text-muted italic', className)} title={reason}>
        {UNAVAILABLE}
        {reason ? <span className="sr-only"> — {reason}</span> : null}
      </span>
    );
  }
  return <span className={cx('tabular', className)}>{value}</span>;
}

// ---- Identifiers ---------------------------------------------------------------------------

export function MonoId({ value, max = 28, label }: { value: string | null | undefined; max?: number; label?: string }) {
  const [copied, setCopied] = useState(false);
  if (!value) return <Value value={null} />;
  const short = middleTruncate(value, max);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };
  return (
    <span className="inline-flex max-w-full items-center gap-1 align-middle">
      <Tip content={<span className="font-mono break-all">{value}</span>}>
        <span tabIndex={0} className="truncate font-mono text-sm" aria-label={label ? `${label}: ${value}` : value}>
          {short}
        </span>
      </Tip>
      <button
        type="button"
        onClick={copy}
        className="rounded p-0.5 text-text-muted hover:text-text"
        aria-label={`Copy ${label ?? 'value'}`}
      >
        {copied ? <Check aria-hidden size={14} /> : <Copy aria-hidden size={14} />}
      </button>
    </span>
  );
}

// ---- Layout --------------------------------------------------------------------------------

export function Card({
  title,
  actions,
  children,
  className,
  as: As = 'section',
  id,
  labelledBy,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  as?: 'section' | 'div' | 'article';
  id?: string;
  labelledBy?: string;
}) {
  const hid = useId();
  return (
    <As
      id={id}
      aria-labelledby={title ? (labelledBy ?? hid) : undefined}
      className={cx('rounded-lg border border-border bg-surface p-3', className)}
    >
      {title || actions ? (
        <div className="mb-2 flex items-center justify-between gap-2">
          {title ? (
            <h3 id={labelledBy ?? hid} className="section-label">
              {title}
            </h3>
          ) : (
            <span />
          )}
          {actions}
        </div>
      ) : null}
      {children}
    </As>
  );
}

export function Skeleton({ className, label = 'Loading' }: { className?: string; label?: string }) {
  return (
    <div role="status" aria-label={label} className={cx('animate-pulse rounded-md bg-surface-raised', className)} />
  );
}

export function KeyValue({ items }: { items: Array<[string, ReactNode]> }) {
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-sm">
      {items.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-text-muted">{k}</dt>
          <dd className="min-w-0 break-words">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function JsonViewer({ value, label }: { value: unknown; label: string }) {
  return (
    <details className="rounded-md border border-border bg-surface-raised">
      <summary className="cursor-pointer px-2 py-1 text-sm text-text-muted">{label}</summary>
      <pre className="max-h-64 overflow-auto px-2 pb-2 font-mono text-sm whitespace-pre-wrap break-all">
        {value === undefined ? 'Not provided' : JSON.stringify(value, null, 2)}
      </pre>
    </details>
  );
}

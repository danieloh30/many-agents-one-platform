import { useEffect } from 'react';

export interface ShortcutHandlers {
  togglePresenter(): void;
  toggleFullscreen(): void;
  openHelp(): void;
  toggleView(): void;
  runPreset(index: number): void;
  prevRun(): void;
  nextRun(): void;
  toggleRail(): void;
  toggleInspector(): void;
}

export const SHORTCUTS: Array<[string, string]> = [
  ['P', 'Toggle presenter mode'],
  ['F', 'Toggle full screen'],
  ['?', 'Show keyboard shortcuts'],
  ['T', 'Toggle topology / timeline'],
  ['Shift + 1…4', 'Run scenario preset 1–4'],
  ['[ / ]', 'Previous / next run'],
  ['L', 'Hide / show the left rail (scenarios and runs)'],
  ['I', 'Hide / show the inspector'],
  ['Esc', 'Close dialogs'],
];

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

export function useShortcuts(h: ShortcutHandlers) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) return;
      if (document.querySelector('[role="dialog"], [role="alertdialog"]')) return;
      const digit = /^Digit([1-4])$/.exec(e.code);
      if (e.shiftKey && digit?.[1]) {
        e.preventDefault();
        h.runPreset(Number(digit[1]) - 1);
        return;
      }
      switch (e.key) {
        case 'p':
        case 'P':
          if (e.shiftKey) return;
          h.togglePresenter();
          break;
        case 'f':
        case 'F':
          if (e.shiftKey) return;
          h.toggleFullscreen();
          break;
        case '?':
          h.openHelp();
          break;
        case 't':
        case 'T':
          if (e.shiftKey) return;
          h.toggleView();
          break;
        case 'l':
        case 'L':
          if (e.shiftKey) return;
          h.toggleRail();
          break;
        case 'i':
        case 'I':
          if (e.shiftKey) return;
          h.toggleInspector();
          break;
        case '[':
          h.prevRun();
          break;
        case ']':
          h.nextRun();
          break;
        default:
          return;
      }
      e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [h]);
}

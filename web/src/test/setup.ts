import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// jsdom gaps used by the app and its libraries.
function mediaQueryList(query: string): MediaQueryList {
  return {
    // Desktop layout; light system theme; motion allowed.
    matches: /min-width/.test(query),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  } as MediaQueryList;
}
Object.defineProperty(window, 'matchMedia', { writable: true, configurable: true, value: vi.fn(mediaQueryList) });

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

class DOMMatrixReadOnlyStub {
  m22 = 1;
  constructor(_t?: string) {}
}
// @ts-expect-error minimal stub for React Flow's transform parsing
globalThis.DOMMatrixReadOnly ??= DOMMatrixReadOnlyStub;

Element.prototype.scrollIntoView ??= function scrollIntoView() {};
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => undefined;

afterEach(() => {
  cleanup();
});

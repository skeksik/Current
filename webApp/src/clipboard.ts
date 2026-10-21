/**
 * What `copyText` did: `copied` to the clipboard, or `selected` the fallback element's text for the user to copy themselves (Ctrl+C or
 * ⌘C), or `failed` when there was neither a clipboard nor a fallback.
 */
export type CopyResult = 'copied' | 'selected' | 'failed';

/**
 * Copies [text] to the clipboard. The Clipboard API exists only in secure contexts (https or localhost), so on a plain-http origin
 * `navigator.clipboard` is undefined; there, or when the browser refuses, the text of [fallback] (an element showing the same text) is
 * selected instead. Callers report the result in a `role="status"` element.
 */
export async function copyText(text: string, fallback?: HTMLElement | null): Promise<CopyResult> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return 'copied';
    }
  } catch {
    // Refused (no permission, or the document lost focus): select the text instead.
  }
  if (!fallback) return 'failed';
  const selection = window.getSelection();
  if (!selection) return 'failed';
  const range = document.createRange();
  range.selectNodeContents(fallback);
  selection.removeAllRanges();
  selection.addRange(range);
  return 'selected';
}

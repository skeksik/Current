// The Clipboard API exists only in a secure context (https or localhost), so a server reached over plain http on the local network has
// none. Resolves to false when there is no clipboard or the browser refused, so the caller can offer the text to copy by hand.
export async function copyText(text: string): Promise<boolean> {
  if (!window.isSecureContext || !navigator.clipboard?.writeText) return false;
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

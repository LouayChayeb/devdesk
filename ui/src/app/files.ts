/**
 * Choosing files to save to or read from. In the desktop app the system's file dialogs give a path and the engine
 * reads or writes the file; in a plain browser (UI development) the page downloads or reads it itself.
 */
const inApp = () => '__TAURI_INTERNALS__' in window;

/** A path to save to, or '' when there is no file dialog (the caller then downloads the text); null when cancelled. */
export async function savePath(defaultName: string, extensions: string[]): Promise<string | null> {
  if (!inApp()) return '';
  const { save } = await import('@tauri-apps/plugin-dialog');
  const path = await save({ defaultPath: defaultName, filters: [{ name: extensions.join(', ').toUpperCase(), extensions }] });
  return typeof path === 'string' ? path : null;
}

/** A file to read: its path in the app, its text in a browser; null when cancelled. */
export async function openFile(extensions: string[]): Promise<{ path: string } | { content: string; name: string } | null> {
  if (inApp()) {
    const { open } = await import('@tauri-apps/plugin-dialog');
    const path = await open({ multiple: false, filters: [{ name: extensions.join(', ').toUpperCase(), extensions }] });
    return typeof path === 'string' ? { path } : null;
  }
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = extensions.map((e) => '.' + e).join(',');
    input.onchange = async () => {
      const f = input.files?.[0];
      resolve(f ? { content: await f.text(), name: f.name } : null);
    };
    input.oncancel = () => resolve(null);
    input.click();
  });
}

export function download(name: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function formatOf(name: string): 'csv' | 'json' | 'sql' | null {
  const ext = name.toLowerCase().split('.').pop();
  return ext === 'csv' || ext === 'json' || ext === 'sql' ? ext : null;
}

/**
 * Opens a second window showing one Workbench tab: a real window in the desktop app, a browser tab otherwise. The
 * window reads the tab from local storage under {@code key} (see Workbench.handOver).
 */
export async function openWindow(key: string, title: string): Promise<void> {
  if (inApp()) {
    const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow');
    new WebviewWindow(`popout-${key}`, { url: `index.html?popout=${key}`, title: `${title} · DevDesk`, width: 1100, height: 760 });
    return;
  }
  const q = new URLSearchParams(location.search);
  q.set('popout', key);
  window.open(`${location.pathname}?${q.toString()}`, '_blank');
}

/** Any file to upload, as a path for the engine; null when cancelled or outside the desktop app (no file dialog). */
export async function pickAnyFile(): Promise<string | null> {
  if (!inApp()) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const path = await open({ multiple: false });
  return typeof path === 'string' ? path : null;
}

/** Where to save a file of any type; null when cancelled or outside the desktop app. */
export async function saveAnyPath(defaultName: string): Promise<string | null> {
  if (!inApp()) return null;
  const { save } = await import('@tauri-apps/plugin-dialog');
  const path = await save({ defaultPath: defaultName });
  return typeof path === 'string' ? path : null;
}

/**
 * A window onto another DevDesk engine (a deploy or a remote dev box), reached through an SSH tunnel on this machine:
 * the same app, talking to that engine instead of the local one.
 */
export async function openEngineWindow(port: number, token: string, title: string): Promise<void> {
  const q = new URLSearchParams({ port: String(port), token });
  if (inApp()) {
    const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow');
    new WebviewWindow(`remote-${port}`, { url: `index.html?${q}`, title: `${title} · DevDesk`, width: 1300, height: 820 });
    return;
  }
  window.open(`${location.pathname}?${q}`, '_blank');
}

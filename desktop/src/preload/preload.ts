// The settings window's only door to the main process: `cloudy.call(name, ...args)` (handlers in main.ts) and
// `cloudy.onUpdate(fn)` for state pushed while the window is open.
import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('cloudy', {
  call: (name: string, ...args: unknown[]) => ipcRenderer.invoke('cloudy', name, ...args),
  onUpdate: (fn: (data: object) => void) => { ipcRenderer.on('cloudy:update', (_e, data) => fn(data)); },
  platform: process.platform,
});

import { createLoadingController } from './loading.js';
import { installRuntimeDiagnostics } from './runtime-diagnostics.js?v=tps-unreal-1';

const loadingRoot = document.querySelector('#loading-screen');
const loading = createLoadingController(loadingRoot);
const diagnostics = installRuntimeDiagnostics();
const retryButton = document.querySelector('#retry-button');
retryButton.addEventListener('click', () => window.location.reload());

async function boot() {
  loading.update('Conectando ao motor 3D', 8);
  try {
    const timeout = new Promise((_, reject) => {
      window.setTimeout(() => reject(new Error('A conexão demorou mais que o esperado.')), 15000);
    });
    const gameModule = await Promise.race([import('./main.js?v=zombie-zone-color-audio-1'), timeout]);
    await gameModule.startApp(loading, diagnostics);
  } catch (error) {
    diagnostics.capture('boot-failure', error);
    console.error(error);
    loading.showError('Não foi possível iniciar o motor 3D local neste aparelho.');
  }
}

boot();

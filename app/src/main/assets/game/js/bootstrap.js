import { createLoadingController } from './loading.js';

const loadingRoot = document.querySelector('#loading-screen');
const loading = createLoadingController(loadingRoot);
const retryButton = document.querySelector('#retry-button');
retryButton.addEventListener('click', () => window.location.reload());

async function boot() {
  loading.update('Conectando ao motor 3D', 8);
  try {
    const timeout = new Promise((_, reject) => {
      window.setTimeout(() => reject(new Error('A conexão demorou mais que o esperado.')), 15000);
    });
    const gameModule = await Promise.race([import('./main.js'), timeout]);
    await gameModule.startApp(loading);
  } catch (error) {
    console.error(error);
    loading.showError('Não foi possível carregar o Three.js. Verifique sua internet.');
  }
}

boot();


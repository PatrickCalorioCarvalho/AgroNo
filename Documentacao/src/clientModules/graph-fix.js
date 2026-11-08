// O plugin docusaurus-graph bloqueia o grafo em telas menores que 800x720
// (valor fixo no código; as opções minWidth/minHeight são ignoradas).
// Aqui a função openGraph é substituída: o grafo abre em qualquer tela de
// tamanho razoável e é escalado para caber na janela.
import siteConfig from '@generated/docusaurus.config';

// O plugin busca '/docusaurus-graph.json' na raiz do domínio, mas o site
// está publicado em /AgroNo/ (baseUrl). Sem este ajuste a busca dá 404 e o
// grafo abre vazio.
if (typeof window !== 'undefined' && !window.__graphFetchPatched) {
  window.__graphFetchPatched = true;
  const originalFetch = window.fetch.bind(window);
  window.fetch = (input, init) => {
    if (input === '/docusaurus-graph.json') {
      input = `${siteConfig.baseUrl}docusaurus-graph.json`;
    }
    return originalFetch(input, init);
  };
}

const MIN_W = 360;
const MIN_H = 320;
const BOX_W = 840; // 800 do canvas + 2 x 20 de padding
const BOX_H = 640; // 600 do canvas + 2 x 20 de padding

if (typeof window !== 'undefined') {
  window.addEventListener('load', () => {
    window.openGraph = function () {
      const container = document.getElementById('graph-container');
      const overlay = document.getElementById('graph-overlay');
      if (!container || !overlay) return;

      if (container.style.display === 'block') {
        window.closeGraph();
        return;
      }
      if (window.innerWidth < MIN_W || window.innerHeight < MIN_H) {
        alert('A tela é pequena demais para exibir o grafo.');
        return;
      }

      const scale = Math.min(
        1,
        (window.innerWidth - 16) / BOX_W,
        (window.innerHeight - 16) / BOX_H,
      );
      container.style.transform = `translate(-50%, -50%) scale(${scale})`;
      container.style.display = 'block';
      overlay.style.display = 'block';
      if (typeof graphViewGlobal !== 'undefined') {
        graphViewGlobal.forceGraph.zoomToFit(1000, 20);
      }
      isOpen = true;
    };
  });
}

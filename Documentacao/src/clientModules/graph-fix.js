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

// Aplica ao grafo as cores do tema atual do site (claro/escuro).
function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
  return v || fallback;
}

function applyGraphTheme() {
  if (typeof graphViewGlobal === 'undefined' || !graphViewGlobal) return;
  const primary = cssVar('--ifm-color-primary', '#2e8555');
  const strong = cssVar('--ifm-color-emphasis-900', '#444');
  graphViewGlobal.updateStyle({
    background: cssVar('--ifm-background-surface-color', '#fff'),
    fontSize: 13,
    highlightedForeground: strong,
    node: { note: { regular: primary } },
    link: {
      regular: cssVar('--ifm-color-emphasis-300', '#ccc'),
      highlighted: primary,
    },
    hoverNodeLink: {
      highlightedDirection: { inbound: primary, outbound: strong },
    },
  });
}

// Espalha os nós e deixa a simulação rodar o bastante para estabilizar
// (o padrão do plugin para em 200 ms e deixa os rótulos sobrepostos).
let forcesTuned = false;
function tuneGraphForces() {
  if (forcesTuned || typeof graphViewGlobal === 'undefined' || !graphViewGlobal) {
    return;
  }
  const fg = graphViewGlobal.forceGraph;
  fg.d3Force('charge').strength(-420);
  fg.d3Force('link').distance(110);
  fg.cooldownTime(3500);
  forcesTuned = true;
  fg.d3ReheatSimulation();
  setTimeout(() => fg.zoomToFit(600, 60), 3700);
}

if (typeof window !== 'undefined') {
  // Reaplica as cores quando o usuário alterna entre tema claro e escuro.
  new MutationObserver(applyGraphTheme).observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme'],
  });

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
      try {
        applyGraphTheme();
        tuneGraphForces();
      } catch (e) {
        console.warn('graph-fix: não foi possível ajustar o grafo', e);
      }
      if (typeof graphViewGlobal !== 'undefined' && graphViewGlobal) {
        graphViewGlobal.forceGraph.zoomToFit(1000, 60);
      }
      isOpen = true;
    };
  });
}

import siteConfig from '@generated/docusaurus.config';

// O plugin docusaurus-graph tem três limitações que este módulo corrige:
//  1. busca '/docusaurus-graph.json' na raiz do domínio, mas o site está em
//     /AgroNo/ (baseUrl): a busca dava 404 e o grafo abria vazio;
//  2. bloqueia o grafo em telas menores que 800x720 (valor fixo no código);
//  3. usa cores fixas, rótulos que desaparecem com o zoom e simulação curta.

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

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
  return v || fallback;
}

// Desenho próprio dos nós: tamanho proporcional ao número de ligações e
// rótulo com contorno da cor de fundo, sempre legível e com a cor do texto
// do site (a biblioteca usa a cor do nó e esmaece o rótulo ao afastar o zoom).
function installNodePainter(gv) {
  const primary = cssVar('--ifm-color-primary', '#2e8555');
  const text = cssVar('--ifm-font-color-base', '#222');
  const bg = cssVar('--ifm-background-surface-color', '#fff');
  const strong = cssVar('--ifm-color-emphasis-900', '#444');

  gv.forceGraph.nodeCanvasObject((node, ctx, scale) => {
    const info = gv.model.nodeInfos[node.id];
    if (!info) return;
    const state = gv.getNodeState(node.id, gv.model);
    const radius = 4 + Math.min(info.neighbors.length, 6) * 0.9;

    ctx.globalAlpha = state === 'lessened' ? 0.2 : 1;

    ctx.beginPath();
    ctx.arc(node.x, node.y, radius, 0, 2 * Math.PI);
    ctx.fillStyle = primary;
    ctx.fill();
    if (state === 'highlighted') {
      ctx.lineWidth = 2 / scale;
      ctx.strokeStyle = strong;
      ctx.stroke();
    }

    const size = 12 / scale;
    ctx.font = `${size}px Sans-Serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    ctx.lineJoin = 'round';
    ctx.lineWidth = 4 / scale;
    ctx.strokeStyle = bg;
    ctx.strokeText(info.title, node.x, node.y + radius + 2 / scale);
    ctx.fillStyle = text;
    ctx.fillText(info.title, node.x, node.y + radius + 2 / scale);

    ctx.globalAlpha = 1;
  });
}

// Cores das arestas e do fundo a partir do tema atual (claro/escuro).
function applyGraphTheme() {
  if (typeof graphViewGlobal === 'undefined' || !graphViewGlobal) return;
  const primary = cssVar('--ifm-color-primary', '#2e8555');
  const strong = cssVar('--ifm-color-emphasis-900', '#444');
  graphViewGlobal.updateStyle({
    background: cssVar('--ifm-background-surface-color', '#fff'),
    highlightedForeground: strong,
    node: { note: { regular: primary } },
    link: {
      regular: cssVar('--ifm-color-emphasis-400', '#bbb'),
      highlighted: primary,
    },
    hoverNodeLink: {
      highlightedDirection: { inbound: primary, outbound: strong },
    },
  });
  // updateStyle recria o desenho padrão dos nós; reinstala o nosso.
  installNodePainter(graphViewGlobal);
}

// Espalha os nós e deixa a simulação estabilizar (o padrão para em 200 ms).
let forcesTuned = false;
function tuneGraphForces() {
  if (forcesTuned || typeof graphViewGlobal === 'undefined' || !graphViewGlobal) {
    return;
  }
  const fg = graphViewGlobal.forceGraph;
  fg.d3Force('charge').strength(-700);
  fg.d3Force('link').distance(130);
  fg.d3Force('collide').radius(48);
  fg.cooldownTime(4000);
  forcesTuned = true;
  fg.d3ReheatSimulation();
  setTimeout(() => fg.zoomToFit(600, 50), 4200);
}

if (typeof window !== 'undefined') {
  // Reaplica as cores ao alternar entre tema claro e escuro.
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

      // O canvas ocupa a janela (até um limite) em vez de ter 800x600 fixos.
      const width = Math.min(1100, window.innerWidth - 80);
      const height = Math.min(720, window.innerHeight - 170);
      container.style.transform = 'translate(-50%, -50%)';
      container.style.display = 'block';
      overlay.style.display = 'block';

      if (typeof graphViewGlobal !== 'undefined' && graphViewGlobal) {
        try {
          graphViewGlobal.updateCanvasSize({ width, height });
          applyGraphTheme();
          tuneGraphForces();
        } catch (e) {
          console.warn('graph-fix: não foi possível ajustar o grafo', e);
        }
        graphViewGlobal.forceGraph.zoomToFit(1000, 50);
      }
      isOpen = true;
    };
  });
}

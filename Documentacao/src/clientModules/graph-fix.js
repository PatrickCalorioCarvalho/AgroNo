import siteConfig from '@generated/docusaurus.config';

// O plugin docusaurus-graph tem limitações que este módulo corrige:
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

// ---------------------------------------------------------------------------
// Organização visual do grafo
// ---------------------------------------------------------------------------
// O plugin só conhece ligações entre páginas. Os grupos (cores) e a trilha de
// leitura numerada abaixo são definidos aqui, à mão. Páginas novas ficam
// cinza e soltas até serem incluídas em GROUPS.
const GROUPS = {
  visao: {
    label: 'Visão geral',
    color: '#4c9be8',
    center: [-330, -150],
    ids: ['intro', 'architecture', 'development'],
  },
  infra: {
    label: 'Infraestrutura IoT',
    color: '#3bb273',
    center: [-80, 130],
    ids: ['index', 'weather-station', 'insect-trap', 'soil-sensor', 'gateway', 'server-api'],
  },
  drone: {
    label: 'Drone e imagens',
    color: '#f59e42',
    center: [210, -150],
    ids: ['drone-mapping', 'drone-pipeline'],
  },
  pesquisa: {
    label: 'Teoria e pesquisa',
    color: '#a872e8',
    center: [450, 130],
    ids: ['teoria-mapeamento', 'pesquisa-drone'],
  },
};

// Trilha de leitura recomendada para entender a pesquisa.
const PATH = [
  'intro',
  'architecture',
  'index',
  'drone-mapping',
  'drone-pipeline',
  'teoria-mapeamento',
  'pesquisa-drone',
];

const groupOf = {};
Object.entries(GROUPS).forEach(([key, g]) => g.ids.forEach((id) => (groupOf[id] = key)));
const pathPos = {};
PATH.forEach((id, i) => (pathPos[id] = i + 1));
const nodeColor = (id) => (groupOf[id] ? GROUPS[groupOf[id]].color : '#8a8f98');

function hexToRgba(hex, alpha) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${n >> 16}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

// Nós: cor do grupo, tamanho pela quantidade de ligações, número da trilha
// e rótulo com contorno da cor de fundo (sempre legível).
function installNodePainter(gv) {
  const text = cssVar('--ifm-font-color-base', '#222');
  const bg = cssVar('--ifm-background-surface-color', '#fff');
  const strong = cssVar('--ifm-color-emphasis-900', '#444');

  gv.forceGraph.nodeCanvasObject((node, ctx, scale) => {
    const info = gv.model.nodeInfos[node.id];
    if (!info) return;
    const state = gv.getNodeState(node.id, gv.model);
    const step = pathPos[node.id];
    const grow = Math.max(1, 0.85 / scale); // mantém nós legíveis quando o grafo é reduzido
    const radius = (5 + Math.min(info.neighbors.length, 6) * 0.9 + (step ? 2 : 0)) * grow;

    ctx.globalAlpha = state === 'lessened' ? 0.18 : 1;

    ctx.beginPath();
    ctx.arc(node.x, node.y, radius, 0, 2 * Math.PI);
    ctx.fillStyle = nodeColor(node.id);
    ctx.fill();
    ctx.lineWidth = (state === 'highlighted' ? 3 : 1.5) / scale;
    ctx.strokeStyle = state === 'highlighted' ? strong : bg;
    ctx.stroke();

    if (step) {
      const bx = node.x + radius * 0.85;
      const by = node.y - radius * 0.85;
      ctx.beginPath();
      ctx.arc(bx, by, 8 * grow, 0, 2 * Math.PI);
      ctx.fillStyle = strong;
      ctx.fill();
      ctx.font = `bold ${10 * grow}px Sans-Serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = bg;
      ctx.fillText(String(step), bx, by + 0.5);
    }

    ctx.font = `${12 / scale}px Sans-Serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    ctx.lineJoin = 'round';
    ctx.lineWidth = 4 / scale;
    ctx.strokeStyle = bg;
    ctx.strokeText(info.title, node.x, node.y + radius + 3 / scale);
    ctx.fillStyle = text;
    ctx.fillText(info.title, node.x, node.y + radius + 3 / scale);

    ctx.globalAlpha = 1;
  });

  // Ligações comuns: finas e discretas, sem seta.
  gv.forceGraph
    .linkColor(() => cssVar('--ifm-color-emphasis-300', '#ccc'))
    .linkWidth(1)
    .linkDirectionalArrowLength(0);
}

// Manchas translúcidas atrás de cada grupo, título do grupo e a trilha
// numerada (curvas com seta ligando as páginas na ordem de leitura).
function installBackdrop(gv) {
  gv.forceGraph.onRenderFramePre((ctx, scale) => {
    const byId = {};
    gv.model.graphData.nodes.forEach((n) => (byId[n.id] = n));

    ctx.save();
    Object.values(GROUPS).forEach((g) => {
      const pts = g.ids.map((id) => byId[id]).filter((n) => n && Number.isFinite(n.x));
      if (!pts.length) return;
      const cx = pts.reduce((a, n) => a + n.x, 0) / pts.length;
      const cy = pts.reduce((a, n) => a + n.y, 0) / pts.length;
      const r = Math.max(...pts.map((n) => Math.hypot(n.x - cx, n.y - cy))) + 55;

      const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
      grad.addColorStop(0, hexToRgba(g.color, 0.2));
      grad.addColorStop(1, hexToRgba(g.color, 0.04));
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, 2 * Math.PI);
      ctx.fillStyle = grad;
      ctx.fill();
      ctx.setLineDash([6 / scale, 5 / scale]);
      ctx.lineWidth = 1.2 / scale;
      ctx.strokeStyle = hexToRgba(g.color, 0.55);
      ctx.stroke();
      ctx.setLineDash([]);

      ctx.font = `bold ${13 / scale}px Sans-Serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.fillStyle = g.color;
      ctx.fillText(g.label.toUpperCase(), cx, cy - r - 5 / scale);
    });

    // Trilha de leitura
    const strong = cssVar('--ifm-color-emphasis-700', '#555');
    ctx.strokeStyle = strong;
    ctx.fillStyle = strong;
    ctx.lineWidth = 2.2 / scale;
    ctx.setLineDash([9 / scale, 6 / scale]);
    for (let i = 0; i < PATH.length - 1; i++) {
      const a = byId[PATH[i]];
      const b = byId[PATH[i + 1]];
      if (!a || !b || !Number.isFinite(a.x) || !Number.isFinite(b.x)) continue;
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const len = Math.hypot(dx, dy) || 1;
      const bend = 0.18 * len;
      const mx = (a.x + b.x) / 2 - (dy / len) * bend;
      const my = (a.y + b.y) / 2 + (dx / len) * bend;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.quadraticCurveTo(mx, my, b.x, b.y);
      ctx.stroke();

      // ponta da seta, perto do destino
      const t = 0.82;
      const px = (1 - t) * (1 - t) * a.x + 2 * (1 - t) * t * mx + t * t * b.x;
      const py = (1 - t) * (1 - t) * a.y + 2 * (1 - t) * t * my + t * t * b.y;
      const tx = 2 * (1 - t) * (mx - a.x) + 2 * t * (b.x - mx);
      const ty = 2 * (1 - t) * (my - a.y) + 2 * t * (b.y - my);
      const ang = Math.atan2(ty, tx);
      const size = 9;
      ctx.setLineDash([]);
      ctx.beginPath();
      ctx.moveTo(px, py);
      ctx.lineTo(px - size * Math.cos(ang - 0.45), py - size * Math.sin(ang - 0.45));
      ctx.lineTo(px - size * Math.cos(ang + 0.45), py - size * Math.sin(ang + 0.45));
      ctx.closePath();
      ctx.fill();
      ctx.setLineDash([9 / scale, 6 / scale]);
    }
    ctx.restore();
  });
}

// Legenda em HTML, abaixo do grafo.
function ensureLegend() {
  if (document.getElementById('graph-legend')) return;
  const host = document.getElementById('graphview-container');
  if (!host || !host.parentElement) return;
  const el = document.createElement('div');
  el.id = 'graph-legend';
  el.innerHTML =
    Object.values(GROUPS)
      .map((g) => `<span><i style="background:${g.color}"></i>${g.label}</span>`)
      .join('') +
    '<span class="graph-legend-path"><b>1 → 7</b> trilha de leitura sugerida</span>';
  host.parentElement.appendChild(el);
}

// Cor do fundo e reinstalação do desenho (updateStyle recria o padrão).
function applyGraphTheme() {
  if (typeof graphViewGlobal === 'undefined' || !graphViewGlobal) return;
  graphViewGlobal.updateStyle({
    background: cssVar('--ifm-background-surface-color', '#fff'),
    highlightedForeground: cssVar('--ifm-color-emphasis-900', '#444'),
  });
  installNodePainter(graphViewGlobal);
  installBackdrop(graphViewGlobal);
}

// Forças: cada grupo é atraído para a sua região; ligações fracas.
let forcesTuned = false;
function tuneGraphForces() {
  if (forcesTuned || typeof graphViewGlobal === 'undefined' || !graphViewGlobal) {
    return;
  }
  const fg = graphViewGlobal.forceGraph;
  const center = (id) => (groupOf[id] ? GROUPS[groupOf[id]].center : [0, 0]);
  fg.d3Force('charge').strength(-500);
  fg.d3Force('link').distance(80).strength(0.04);
  fg.d3Force('collide').radius(66);
  fg.d3Force('x').x((n) => center(n.id)[0]).strength((n) => (groupOf[n.id] ? 0.22 : 0.02));
  fg.d3Force('y').y((n) => center(n.id)[1]).strength((n) => (groupOf[n.id] ? 0.22 : 0.02));
  fg.cooldownTime(4000);
  forcesTuned = true;
  fg.d3ReheatSimulation();
  setTimeout(() => fg.zoomToFit(600, 80), 4200);
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
      const height = Math.min(720, window.innerHeight - 260);
      container.style.transform = 'translate(-50%, -50%)';
      container.style.display = 'block';
      overlay.style.display = 'block';

      if (typeof graphViewGlobal !== 'undefined' && graphViewGlobal) {
        try {
          graphViewGlobal.updateCanvasSize({ width, height });
          applyGraphTheme();
          tuneGraphForces();
          ensureLegend();
        } catch (e) {
          console.warn('graph-fix: não foi possível ajustar o grafo', e);
        }
        graphViewGlobal.forceGraph.zoomToFit(1000, 80);
      }
      isOpen = true;
    };
  });
}

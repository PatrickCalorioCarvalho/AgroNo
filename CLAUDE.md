# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

AgroNó é uma plataforma IoT agrícola (ESP32 + Raspberry Pi, offline-first). O repositório é um monorepo de **pesquisa/documentação**, com pouco código executável. O conteúdo está em português (docs, commits, README) — mantenha o idioma. Commits seguem Conventional Commits (`feat(docs): ...`, `fix(docs): ...`, `style(docs): ...`).

Subprojetos:

- `Documentacao/` — site Docusaurus 3 (a parte mais ativa do repo). Fonte de verdade da arquitetura (gateway, nós, server-api, pipeline de drone, teoria de mapeamento).
- `Drone/MapaDrone/` — app Android **AgroNo Fly** (pasta e package continuam `MapaDrone`/`com.mapadrone.app`: a App Key da DJI esta presa ao package; trocar exige nova chave) (Kotlin, DJI Mobile SDK v4) que planeja e executa voo de mapeamento em grade com Mavic Pro. Nunca testado com drone real. Ver o README do módulo para App Key da DJI (`local.properties`, modelo em `local.properties.example`) e avisos de segurança.
- `Backend/` — servidor **AgroNo Fly** (Python; `python Backend/server.py`, porta 8000). Recebe o voo enviado pelo app (rota planejada, `areaName`, parametros) e as fotos (`Backend/upload_fotos.py <voo_id> <pasta>`), guarda por voo (`<voo_id>/voo.json`, `originais/`, `miniaturas/`) em disco ou em RustFS/S3 (variaveis `AGRONO_S3_*`; boto3/Pillow opcionais) e serve uma pagina em `GET /` (mapa com poligono/rota/H, galeria, voos agrupados por nome da area). Subir com Docker: `Backend/docker-compose.yml` (rustfs + api, credenciais no `Backend/.env`, ver `.env.example`); `docker-compose.edge.yml` adiciona labels do Traefik (rota `/agrono-fly`) para a vm-docker. URLs do front sao relativas de proposito (funciona atras de prefixo). Sem autenticacao: so rede local. O modo S3 foi testado no RustFS real do servidor (so a API/fotos). Mosaico: `POST /api/voos/<id>/processar` poe o voo numa fila (estado em `<voo_id>/processamento.json`); o `Backend/worker.py` (servico `agrono-fly-worker`) pega o proximo, baixa as fotos, processa no NodeODM (servico `agrono-fly-nodeodm`, imagem grande e precisa de RAM) e devolve `mosaico/orthophoto.tif` + `mosaico.jpg`; a pagina tem o botao "Gerar mosaico". Fila simples (sem Redis/RabbitMQ). Testado com o ODM real ate gerar o ortomosaico (voo de 38 fotos); a previa JPG de um GeoTIFF real e a recuperacao de tarefa antiga ainda nao foram vistas funcionando. A pagina tem o **Simulador de malha** (JS em `INDEX_HTML`, porta do `GridPlanner.kt`: manter os dois iguais ao mudar um). O app tambem baixa as fotos do SD do drone pelo controle (`PhotoTransfer.kt`, Mais > Enviar fotos do voo), ainda nao testado com o drone. Para testar com o tablet por USB: `adb reverse tcp:8000 tcp:8000` e URL `http://localhost:8000` em Config.
- `deploy.ps1` (raiz, **no .gitignore**, como no CLKThingsManager) — empacota `Backend/`, envia por scp e roda `docker compose up -d --build` (`-Down` faz `down` antes) no servidor (pede host/usuario SSH; a senha e pedida pelo ssh). Os servidores de exemplo (192.168.18.x) so respondem na rede/VPN deles.
- `Dash/main.py` — protótipo Streamlit + Folium da topologia mesh (dados simulados, sem banco).
- `Proposta/` — material da proposta de pesquisa (docx/pdf); está no `.gitignore` e fora do escopo do repo.

## Comandos

Documentação (rodar em `Documentacao/`, Node >= 18; CI usa Node 20):

```
npm ci
npm start          # dev server
npm run build      # build de produção; onBrokenLinks: 'throw' faz links quebrados falharem o build
npm run serve      # serve o build local
```

App Android (rodar em `Drone/MapaDrone/`):

```
gradlew testDebugUnitTest      # testes do gerador de grade (GridPlannerTest)
gradlew testDebugUnitTest --tests "com.mapadrone.app.core.GridPlannerTest"
gradlew assembleDebug
```

Dashboard: `streamlit run Dash/main.py` (precisa de streamlit, pandas, folium, streamlit-folium; não há requirements.txt).

Não há lint configurado em nenhum subprojeto.

## Arquitetura da documentação

- **Deploy**: push em `main` que toque `Documentacao/**` dispara `.github/workflows/deploy-docs.yml`, que roda `npm ci && npm run build` e publica `Documentacao/build` no branch `gh-pages` (GitHub Pages em `https://patrickcaloriocarvalho.github.io/AgroNo/`, por isso `baseUrl: '/AgroNo/'`).
- **Front-matter `sources:`** nos `.md` de `docs/` lista de quais outros docs aquele derivou (ex.: `sources: [intro]`). Preserve ao editar/criar docs; o grafo de navegação depende dos links entre docs.
- **Grafo de navegação**: plugin `docusaurus-graph` lê `static/docusaurus-graph.json` (nós com `linkTo`/`referencedBy`). O plugin tem limitações corrigidas em runtime por `src/clientModules/graph-fix.js` (reescreve o `fetch` do JSON para respeitar o `baseUrl`, remove o limite mínimo de tela, aplica tema/cores/rótulos). Se mudar `baseUrl` ou o plugin, revise esse módulo. Ao adicionar/remover docs ou links entre eles, o JSON do grafo precisa refletir isso.
- Diagramas são Mermaid (`@docusaurus/theme-mermaid`, tema `handDrawn`). Idioma/locale do Docusaurus está `en`, mas o conteúdo é pt-BR.

## Arquitetura do app MapaDrone

Pacote `com.mapadrone.app`: `core/` é lógica pura e testável (`GridPlanner` gera a serpentina com sobreposição, `CameraModel` calcula footprint/espaçamento, `Geo` utilidades geográficas); `DroneManager` encapsula o DJI SDK e a execução de missão; `MapaApp`/`MainActivity` UI e registro do SDK; `data/FlightStore` persiste o histórico de voos (ID `AAAA-MM-DD_NNN`). A UI e fixa em paisagem, tema escuro (mapa com filtro de cores invertidas), painel de acoes a direita; os parametros do voo ficam no modal Config (junto com a URL do servidor, em SharedPreferences `cfg`). Cada voo e salvo como JSON local (inclui `route`, `home`, `resumeIndex`, `synced`); `syncPending()` envia os nao sincronizados ao servidor, e `Mais > Salvar plano atual` / `Planos salvos` (`data/PlanStore`) repete o mesmo voo (cantos + H + parametros; a rota e recalculada), e `Mais > Continuar voo anterior` recria o plano e pula os pontos ja fotografados. Com bateria <= 35% a missao e PARADA (nao pausada: em pausa o drone ficava em modo waypoint sem obedecer ao controle) e o voo vira `pausado por bateria` para `Continuar voo anterior`. Partes sao fatias do mesmo tamanho da rota inteira (cortam linhas), e ao terminar uma parte o app inicia a proxima sozinho (contagem de 8 s, cancela com PARAR) se a bateria cobrir a parte (35% + 5%/min estimado + 20%); senao espera a troca. O drone mede ~4 s por waypoint (`secondsPerWaypoint`) e ~12 min de missao por bateria. O log do app registra modo de voo, RTH e estado da missao DJI (`DJI: modo de voo=...`). `data/AppLog` grava `files/applog/app.log` (eventos, erros DJI, excecoes e CRASH via UncaughtExceptionHandler; 1 MB) e `syncPending()` envia as linhas novas + erros do logcat do app para `PUT /api/logs/<aparelho>_<data>.log` (servidor guarda em `_logs/`; pagina: botao Logs do app, com filtro). Voo que falha ao iniciar ou e parado com 0 fotos e descartado (nao vira registro; o motivo fica no AppLog); Parar sem missao em execucao nao altera o registro; `Mais > Historico > Excluir vazios` limpa os antigos e chama `DELETE /api/voos/<id>` (o servidor so apaga voo sem fotos nem mosaico). Fotos a mais de um voo: `Backend/apagar_fotos.py` (usa `DELETE /api/voos/<id>/fotos/<arq>`; mantem as ultimas N pela numeracao DJI). Para ver o log direto no tablet: `adb shell run-as com.mapadrone.app cat files/applog/app.log`. Restrição de domínio: missões são divididas em partes de até 95 waypoints (limite do Mavic Pro é 99). As fotos ficam no SD do drone e o mosaico é gerado fora do app (WebODM).

## Cuidados

- `docssync.yaml` (raiz) contém tokens/chaves de serviços de sync (GitLab e OpenWebUI) e está no `.gitignore` — nunca commitar nem reproduzir seu conteúdo. `.precommit/` também é ignorado.
- `README.md` da raiz referencia links do site em `agrono-docs.netlify.app`, enquanto o deploy atual é GitHub Pages.

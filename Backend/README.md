# AgroNo Fly (backend de voos)

Servidor Python que recebe o voo do app Mapa Drone (rota planejada, área, parâmetros) e as fotos do cartão SD, guarda tudo por voo e mostra numa página web (mapa + galeria). A página usa URLs relativas, então funciona atrás de proxy com prefixo (ex.: Traefik em `/agrono-fly`).

## Subir

Só Python (modo disco, pasta `./data`; boto3 e Pillow são opcionais):

```bash
python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
python server.py --port 8000
```

Página: <http://127.0.0.1:8000/> (no Windows prefira `127.0.0.1` a `localhost`, que tenta IPv6 primeiro e atrasa).

### Docker: RustFS (S3) + API

```bash
cp .env.example .env        # defina AGRONO_S3_ACCESS_KEY e AGRONO_S3_SECRET_KEY
docker compose up -d --build
```

API e página em :8000, API S3 do RustFS em :9000, console em :9001. O bucket é criado se não existir. Para rodar a API fora do Docker apontando para o RustFS: `AGRONO_S3_ENDPOINT=http://127.0.0.1:9000 AGRONO_S3_ACCESS_KEY=... AGRONO_S3_SECRET_KEY=... python server.py`.

### Atrás do gateway Traefik

```bash
docker compose -f docker-compose.yml -f docker-compose.edge.yml up -d --build
```

Publica a API em `/agrono-fly` (stripprefix) pela rede externa `edge`. O compose principal não depende dessa rede.

| Variável | Padrão | Uso |
|---|---|---|
| `AGRONO_S3_ENDPOINT` | — | liga o modo S3 (junto com as duas chaves; no compose já aponta para o RustFS) |
| `AGRONO_S3_ACCESS_KEY` / `AGRONO_S3_SECRET_KEY` | — (obrigatórias no compose) | credenciais do RustFS/S3 |
| `AGRONO_S3_BUCKET` | `agrono-drone` | bucket |
| `AGRONO_FLY_API_PORT` / `AGRONO_FLY_S3_PORT` / `AGRONO_FLY_CONSOLE_PORT` | 8000 / 9000 / 9001 | portas publicadas no host |

## Layout dos dados (disco ou bucket)

```
<voo_id>/voo.json
<voo_id>/originais/<foto>
<voo_id>/miniaturas/<foto>.jpg     480 px, gerada no upload se o Pillow estiver instalado
```

## Rotas

`GET /` · `GET /health` · `GET|POST /api/voos` · `GET /api/voos/<id>` · `GET /api/voos/<id>/fotos` · `PUT|GET /api/voos/<id>/fotos/<arquivo>` · `GET /api/voos/<id>/miniaturas/<arquivo>` · `DELETE /api/voos/<id>` (so voo vazio) · `DELETE /api/voos/<id>/fotos/<arquivo>` · `PUT|GET /api/logs/<arquivo>` · `GET /api/logs`

Fotos de outro voo que entraram por engano saem com `python apagar_fotos.py <voo_id> [--manter N] [--apagar] --server <url>` (mantem as ultimas N, que e o numero de disparos do voo; sem `--apagar` so mostra).

## Fluxo

1. No app, defina a URL do servidor em **Config** e o nome da área; o voo é enviado sozinho (ou em **Mais → Sincronizar**).
2. Copie as fotos do cartão SD para uma pasta e rode `python upload_fotos.py <voo_id> <pasta> --server http://127.0.0.1:8000`.
3. Abra a página, escolha o voo (agrupados pelo nome da área) e confira rota, pontos e fotos.

Sem autenticação: use só em rede local confiável.

## Mosaico (worker + NodeODM)

`docker compose up -d --build` sobe tambem `agrono-fly-nodeodm` (OpenDroneMap, imagem grande, recomenda-se >= 8 GB de RAM)
e `agrono-fly-worker`. Na pagina, abra um voo com pelo menos 3 fotos e clique em **Gerar mosaico** (rapido ou completo).
O estado (fila, processando, concluido, erro) aparece na hora; o resultado fica em `<voo_id>/mosaico/`
(`orthophoto.tif` para abrir no QGIS e `mosaico.jpg` de previa).

A fila e simples: o estado de cada voo fica em `processamento.json` e o worker consulta `POST /api/fila/proximo`.
Um voo "processando" sem sinal do worker por 30 min volta para a fila. Testado so com um NodeODM falso.

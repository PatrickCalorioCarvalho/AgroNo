# Teoria do Mapeamento Aéreo

Esta página reúne a fundamentação técnica usada pelo [Drone de Mapeamento](nodes/drone-mapping.md) e pela [pesquisa](pesquisa-drone.md). É a versão resumida do Apêndice A do documento de apoio da proposta de mestrado (PPGI/UTFPR), na pasta `Proposta` do repositório. O edital limita a proposta enviada a 6 páginas, então a teoria completa não cabe nela.

## Geometria de captura

Com a câmera apontada para baixo e a foto em paisagem (lado longo perpendicular ao sentido do voo), a área vista no chão depende só da altura de voo `H` e da câmera. Sendo `w` e `h` a largura e a altura do sensor e `f` a distância focal:

```text
L   = H * w / f          largura no solo (transversal ao voo)
l   = H * h / f          comprimento no solo (ao longo do voo)
GSD = L / Nx             resolução no solo (Nx = pixels do lado longo)
d   = l * (1 - Of)       distância entre fotos (Of = sobreposição frontal)
e   = L * (1 - Ol)       distância entre linhas (Ol = sobreposição lateral)
```

### Câmera adotada (Mavic Pro, valores aproximados)

| Parâmetro | Valor |
|---|---|
| Sensor (largura por altura) | 6,17 por 4,55 mm (1/2,3 polegada) |
| Distância focal real | cerca de 4,73 mm |
| Imagem | 4000 por 3000 pixels (12 MP) |

### Efeito da altura (75% frontal e 70% lateral)

| Altura | Cobertura por foto | Resolução (GSD) | Entre fotos | Entre linhas |
|---|---|---|---|---|
| 8 m | 10,4 por 7,7 m | 0,26 cm por pixel | 1,9 m | 3,1 m |
| 20 m | 26,1 por 19,2 m | 0,65 cm por pixel | 4,8 m | 7,8 m |
| 30 m | 39,1 por 28,9 m | 0,98 cm por pixel | 7,2 m | 11,7 m |

Valores teóricos, a confirmar em campo. A distância real entre linhas é ajustada para dividir a área em partes iguais, ficando igual ou menor que a da tabela.

## Planejamento da grade

1. Os cantos marcados formam um polígono, projetado em um plano local em metros em torno do primeiro canto.
2. Escolhe-se, entre as direções das arestas, a do menor retângulo que envolve o polígono. O voo segue o lado mais comprido, o que reduz o número de curvas.
3. As linhas externas são recuadas para dentro por `a = max(0, L/2 - e)`, de modo que todo ponto da área seja visto por pelo menos duas linhas. O número de linhas é `n = ceil((W - 2a) / e) + 1`, com `W` a largura da área.
4. Cada linha se estende por uma margem `M` nas duas pontas, com `m = ceil((C + 2M) / d) + 1` fotos por linha, sendo `C` o comprimento da área. O sentido alterna a cada linha (serpentina).
5. Cada ponto vira um waypoint com pausa de 0,7 s e disparo da foto. Missões com mais de 95 pontos são divididas em partes, respeitando o limite de 99 do equipamento.

Exemplo (terreno de 12 por 50 m a 8 m de altura): 4 linhas, 31 fotos por linha, 124 fotos em 2 partes de 62 pontos, cerca de 9 minutos. Esses valores são reproduzidos pelos testes automatizados do gerador de grade.

## Índices de vegetação em RGB

Sendo `R`, `G` e `B` os canais vermelho, verde e azul, e `r`, `g` e `b` as coordenadas cromáticas (cada canal dividido por `R + G + B`):

```text
VARI = (G - R) / (G + R - B)
ExG  = 2g - r - b
GLI  = (2G - R - B) / (2G + R + B)
```

Sem calibração radiométrica e com exposição automática, esses valores são **relativos**: servem para comparar regiões dentro de um mesmo mosaico e, com cautela, entre datas. Não equivalem ao NDVI.

## Métricas de avaliação

```text
Precisão   = VP / (VP + FP)
Revocação  = VP / (VP + FN)
F1-score   = 2 * Precisão * Revocação / (Precisão + Revocação)
IoU        = área(A interseção B) / área(A união B)
Erro médio da contagem = média de abs(n previsto - n de referência) por parcela
```

O IoU decide se uma detecção corresponde a uma anotação (o limite mínimo é um parâmetro a definir no experimento).

## Sincronização offline-first

Cada registro recebe um identificador único (nó, instante e sequência). O gateway grava com sincronização falsa e envia em lote. O servidor insere de forma idempotente (repetir o mesmo identificador não duplica) e responde com ACK, e só então o gateway marca como sincronizado.

- **Completude**: registros únicos no servidor divididos pelos gerados nos nós.
- **Taxa de sucesso**: lotes confirmados divididos pelos enviados após a volta da conexão.

Os ensaios induzem quedas de conexão de duração e frequência controladas.

## Limitações do equipamento

- Câmera somente RGB, sem NDVI real e sem calibração radiométrica.
- Exposição automática, que pode variar entre fotos e voos.
- Obturador rolling shutter, que reduz a precisão geométrica em voo contínuo. O voo com parada em cada ponto mitiga o efeito.
- Autonomia de cerca de 20 minutos por bateria e limite de 99 waypoints por missão.
- Equipamento e SDK sem atualizações pela fabricante.

## Referências das técnicas

As referências completas (VARI, ExG, GLI, YOLO e U-Net) estão na seção 7 do documento de apoio, na pasta `Proposta` do repositório. Confira os dados de cada uma antes de citá-las em qualquer texto.

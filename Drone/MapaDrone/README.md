# Mapa Drone (MVP)

App Android para planejar e executar um voo de mapeamento em grade com o DJI Mavic Pro (1a geracao), usando o DJI Mobile SDK v4.

## O que faz
- Marca os cantos da area com o proprio drone (botao "Marcar canto") ou por toque longo no mapa.
- Gera linhas paralelas (serpentina) com a sobreposicao pedida, camera a -90 graus; cada ponto da grade e uma foto.
- Mostra o trajeto no mapa antes do voo, com estimativa de fotos, distancia e tempo.
- Divide a missao em partes de ate 95 pontos (limite do Mavic Pro: 99 por missao).
- Executa, pausa, retoma e para a missao. Ao terminar o drone fica parado no ar.
- Guarda um registro por voo (ID `AAAA-MM-DD_NNN`, data, parametros, cantos, fotos disparadas, log) em Historico.
- As fotos ficam no cartao SD do drone; o mosaico se gera no PC (WebODM).

## Antes de instalar: App Key da DJI
1. Crie conta em https://developer.dji.com e registre um app Android com package name `com.mapadrone.app`.
2. Cole a chave em `local.properties`: `DJI_APP_KEY=sua_chave`
3. Recompile (`gradlew assembleDebug`). Sem chave valida o app abre, mas nao conecta ao drone.
4. Na primeira abertura o celular precisa de internet para ativar o SDK.

## Compilar e instalar
```
gradlew testDebugUnitTest     # testes do gerador de grade
gradlew assembleDebug         # gera app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Ou copie o APK para o celular e permita "instalar apps de fontes desconhecidas".

## Seguranca (leia)
- Decole manualmente; o app so inicia a missao com o drone ja voando, GPS >= 8 satelites e bateria >= 30%.
- O drone nao enxerga obstaculos laterais: percorra o trajeto antes, mantenha 5 m de margem de fios, postes e muros.
- Teste em etapas: 15 m, depois 10 m, depois 8 m.
- Regras ANAC/DECEA se aplicam.

## Limitacoes do MVP
- Nunca foi testado com um drone real: valide em area aberta, missao curta, com o controle remoto na mao.
- Altura e relativa ao ponto de decolagem (terreno plano).
- A contagem de fotos e a de pontos concluidos; confira o total no cartao SD.
- Mapa OpenStreetMap (precisa de internet para carregar os blocos do mapa).

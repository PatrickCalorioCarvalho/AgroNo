# Arquitetura do Sistema AgroNó

## Visão Geral da Arquitetura

```mermaid
graph TD
    A[Nós IoT<br/>ESP32] -->|Rádio NRF24/LoRa| B[Gateway AgroNó<br/>Raspberry Pi]
    B --> C{Internet<br/>Disponível?}
    C -->|Sim| D[Servidor AgroNó<br/>API/Banco de Dados]
    C -->|Não| E[Buffer Offline<br/>SQLite/JSON]
    E --> F[Sincronização<br/>Quando Online]
    F --> D
    D --> G[Dashboards<br/>Relatórios]
```

## Componentes Detalhados

### Nós IoT

```mermaid
graph LR
    subgraph "Nó IoT (ESP32)"
        A1[Sensor] --> B1[Processamento]
        B1 --> C1[Rádio]
        C1 --> D1[Transmissão]
        E1[Energia Solar] --> F1[Deep Sleep]
    end
```

### Gateway AgroNó

```mermaid
graph TD
    A[Recepção Rádio] --> B[Armazenamento Local]
    B --> C{Internet?}
    C -->|Sim| D[Sincronização]
    C -->|Não| E[Buffer Offline]
    D --> F[ACK]
    F --> G[Marcar Sincronizado]
```

### Fluxo de Mensagens

```mermaid
sequenceDiagram
    participant Nó
    participant Gateway
    participant Servidor

    Nó->>Gateway: Enviar Dado via Rádio
    Gateway->>Gateway: Salvar Localmente
    Gateway->>Gateway: Verificar Internet
    alt Internet Disponível
        Gateway->>Servidor: Enviar Dado
        Servidor->>Gateway: ACK
        Gateway->>Gateway: Marcar Sincronizado
    else Sem Internet
        Gateway->>Gateway: Manter no Buffer
    end
```

## Comunicação

### PoC Doméstica (NRF24)

```mermaid
graph LR
    A[ESP32 Nó] -->|NRF24 2.4GHz| B[ESP32 Gateway]
    B -->|Wi-Fi| C[Servidor Local]
```

### Campo Real (LoRa)

```mermaid
graph LR
    A[ESP32 Nó] -->|LoRa 915MHz| B[Raspberry Gateway]
    B -->|4G/LTE| C[Servidor VPS]
```

## Evolução do Sistema

```mermaid
timeline
    title Roadmap AgroNó
    2025 : PoC Doméstica
    2026 : Piloto Campo
           : Gateway Redundante
    2027 : Dashboard Avançado
           : Open Hardware
           : Open Firmware
```
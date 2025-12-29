# 🌱 AgroNó - Plataforma IoT Agrícola

<p align="center">
  <img src="Documentacao/static/img/logo.svg" alt="AgroNó Logo" width="200"/>
</p>

<p align="center">
  <strong>Plataforma IoT Agrícola de Baixo Custo • Offline-First • Open Source</strong>
</p>

<p align="center">
  <a href="#visão-geral">Visão Geral</a> •
  <a href="#arquitetura">Arquitetura</a> •
  <a href="#instalação">Instalação</a> •
  <a href="#documentação">Documentação</a> •
  <a href="#contribuição">Contribuição</a>
</p>

---

## 🌟 Visão Geral

O **AgroNó** é uma plataforma completa de Internet das Coisas (IoT) desenvolvida especificamente para monitoramento agrícola em ambientes rurais com conectividade limitada ou inexistente.

### ✨ Características Principais

- **🔋 Offline-First**: Funciona sem internet, sincronizando quando disponível
- **💰 Baixo Custo**: Hardware acessível baseado em ESP32 e Raspberry Pi
- **🔄 Escalável**: Suporte a múltiplos nós e gateways
- **🌐 Open Source**: Código aberto e hardware documentado
- **📊 Dashboards**: Visualização em tempo real dos dados agrícolas

## 🏗️ Arquitetura

```mermaid
graph TD
    A["🌤️<br/>Estação Meteorológica"] -->|Dados Meteorológicos| B["📡<br/>Gateway"]
    C["🌱<br/>Sensor de Solo"] -->|Dados de Solo| B
    D["🐛<br/>Armadilha de Insetos"] -->|Contagem de Insetos| B
    B -->|Dados Agregados| E["🖥️<br/>Servidor API"]
    E --> F[📈 Dashboards e Relatórios]

    click A "https://agrono-docs.netlify.app/docs/nodes/weather-station"
    click B "https://agrono-docs.netlify.app/docs/gateway"
    click C "https://agrono-docs.netlify.app/docs/nodes/soil-sensor"
    click D "https://agrono-docs.netlify.app/docs/nodes/insect-trap"
    click E "https://agrono-docs.netlify.app/docs/server-api"
```

## 📄 Licença

Este projeto está sob a licença MIT - veja o arquivo [LICENSE](LICENSE) para detalhes.


<p align="center">
  <strong>Feito com ❤️ para agricultura sustentável</strong>
</p>


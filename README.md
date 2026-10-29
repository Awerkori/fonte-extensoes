# Project Nox · Código-fonte das Extensões de Mangá

Repositório central de desenvolvimento, código-fonte em Kotlin e automação de compilação das extensões de mangá do **Project Nox** para **Mihon e forks**.

---

> [!IMPORTANT]
> **Procurando apenas instalar as extensões no seu aplicativo?**  
> Este repositório destina-se a desenvolvedores e contém o código-fonte em Kotlin para desenvolvimento e manutenção. Para instalar ou atualizar os catálogos prontos no seu leitor, utilize o repositório de distribuição oficial:  
> ➡️ [**Project Nox · Extensões de Mangá (Distribuição)**](https://github.com/Awerkori/manga-extensoes)

---

## 🎯 Sobre o Projeto

O ecossistema de mangás do **Project Nox** mantém fontes ativas e atualizadas com foco na comunidade brasileira (PT-BR) e fontes selecionadas. As principais responsabilidades deste projeto incluem:

- **Desenvolvimento e Manutenção Contínua:** Implementação de parsers em Kotlin baseados na API de extensões para **Mihon e forks**, contornando proteções anti-bot, atualizando seletores CSS/JSON e garantindo estabilidade no carregamento de capítulos.
- **Pipeline de Integração Contínua (CI):** Compilação automatizada via Gradle e GitHub Actions, com validação de tipos, testes sintéticos de catalogação e empacotamento em APKs assinados.
- **Sincronização Upstream com Guardião de Fontes:** Mecanismo autônomo que importa melhorias da base global upstream enquanto protege de forma determinística as fontes e customizações exclusivas desenvolvidas pelo Project Nox.
- **Publicação Automática:** Cada alteração aprovada na branch principal compila os pacotes e publica os novos artefatos e o índice Protobuf/JSON diretamente no repositório [`manga-extensoes`](https://github.com/Awerkori/manga-extensoes).

---

## 🏗️ Estrutura do Repositório

O projeto é organizado de forma modular em Gradle:

```
├── src/                    # Extensões separadas por idioma
│   ├── pt/                 # Extensões em Português (Brasil) — foco principal
│   ├── all/                # Extensões multisource globais
│   └── ...                 # Outros idiomas mantidos
├── lib/                    # Bibliotecas auxiliares compartilhadas entre extensões
├── lib-multisrc/           # Temas multisource compartilhados (Madara, MangaThemesia, etc.)
├── core/                   # Utilitários centrais de catalogação e testes unitários
├── .github/
│   ├── workflows/          # Workflows de CI (build_push.yml, sync_upstream.yml)
│   └── scripts/            # Scripts em Python para geração do catálogo e sincronização
└── build.gradle.kts        # Configurações globais de compilação Gradle
```

---

## 💻 Como Compilar e Desenvolver Localmente

### Pré-requisitos
- **JDK 17** ou superior instalado e configurado no ambiente (`JAVA_HOME`).
- **Android SDK** com Build-Tools e Platform-Tools atualizados (Android SDK 30+).

### Clonar o projeto
```bash
git clone https://github.com/Awerkori/manga-fonte-extensoes.git
cd manga-fonte-extensoes
```

### Comandos de Compilação

Para compilar uma extensão específica em modo debug:
```bash
# Exemplo: compilar uma extensão em português
./gradlew :src:pt:<nome-da-extensao>:assembleDebug
```
O arquivo APK compilado será gerado em:  
`src/pt/<nome-da-extensao>/build/outputs/apk/debug/`

### Verificação de Qualidade e Formatação
```bash
# Executar testes unitários do núcleo
./gradlew test

# Verificar e aplicar a formatação padrão do código (Spotless)
./gradlew spotlessApply
```

---

## 🤝 Como Contribuir

Contribuições da comunidade são muito bem-vindas:

1. **Consulte a Central de Pedidos:** Antes de iniciar o desenvolvimento, verifique na [Central de Pedidos](https://github.com/Awerkori/project-nox-requests) se já existe uma demanda aberta ou em andamento.
2. **Crie uma branch semântica:** Baseie-se na branch `main` e nomeie de forma clara (ex: `fix/nome-da-fonte` ou `feat/nova-fonte`).
3. **Mantenha os padrões técnicos:** Use seletores estáveis, evite dependências desnecessárias e garanta que `./gradlew spotlessApply` seja executado antes do commit.
4. **Envie seu Pull Request:** Descreva as alterações realizadas, os testes feitos no emulador ou dispositivo físico e anexe evidências se apropriado.

---

## 🛠️ Suporte e Reporte de Erros

Se encontrar uma extensão que parou de carregar imagens, apresentou erro de decodificação ou sofreu alteração de layout no site de origem:

- 🎫 **Abrir solicitação oficial:** [Project Nox · Central de Pedidos](https://github.com/Awerkori/project-nox-requests)
- 📊 **Acompanhar status em tempo real:** [Project Nox · Tracker](https://awerkori.github.io/project-nox-tracker/)

---

## 🔗 Projetos Relacionados

- 📦 [**Project Nox · Extensões de Mangá**](https://github.com/Awerkori/manga-extensoes) — Repositório de distribuição (APKs compilados e índice para o aplicativo).
- 🎬 [**Project Nox · Extensões de Anime**](https://github.com/Awerkori/anime-extensoes) — Catálogo de extensões de anime para Aniyomi e forks.
- 💻 [**Project Nox · Código-fonte das Extensões de Anime**](https://github.com/Awerkori/anime-fonte-extensoes) — Código-fonte e extractors de vídeo.
- 🌐 [**Project Nox · Plataforma Web de Mangá**](https://github.com/Awerkori/project-nox-manga) — Leitor web oficial do Project Nox.
- ⚙️ [**Project Nox · Importer**](https://github.com/Awerkori/project-nox-importer) — Serviço de ingestão contínua de catálogo 24/7.

---

## 🌟 Créditos e Atribuições

O **Project Nox** expressa seu reconhecimento e agradecimento a todos que tornaram este projeto possível:

- **Felipe GFA** ([@FelipeGFA](https://github.com/FelipeGFA)): Contribuição fundamental na concepção e engenharia do projeto, responsável pela criação e manutenção de dezenas de extensões da comunidade brasileira (Nexus, Manhastro, MangaLivre, Yomu, LycanToons, PlumaComics, MangoTheme, entre outras), pela implementação dos mecanismos avançados de sincronização upstream e pelo pipeline de publicação do catálogo Protobuf.
- **Keiyoushi** ([@keiyoushi](https://github.com/keiyoushi)): Pela base upstream e arquitetura de referência do ecossistema de extensões de leitura.
- **Javier Tomás** e colaboradores históricos do ecossistema open-source de mangás.

---

## ⚖️ Licença e Isenção de Responsabilidade

Distribuído sob a licença **Apache 2.0**. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.

```
Copyright 2015 Javier Tomás
Copyright (c) Keiyoushi Contributors
Copyright (c) Project Nox Contributors & Felipe GFA

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

### Isenção de Responsabilidade
O **Project Nox** desenvolve exclusivamente ferramentas de código aberto para interoperabilidade técnica e agregação programática. O projeto não hospeda, gerencia nem armazena conteúdos protegidos por direitos autorais em seus repositórios de código. As extensões realizam apenas requisições diretas a fontes publicamente acessíveis na web.

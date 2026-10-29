# Project Nox · Código-fonte das Extensões de Mangá

Repositório central de código-fonte, automação de compilação e controle de qualidade das extensões de mangá do **Project Nox** para **Mihon**, **TachiyomiSY** e forks compatíveis.

---

> [!NOTE]
> **Procurando apenas instalar as extensões no seu leitor?**  
> Este repositório contém o código-fonte em Kotlin para desenvolvimento. Se você é leitor e deseja adicionar as fontes ao seu aplicativo, acesse o repositório de distribuição compilada:  
> ➡️ [**Project Nox · Extensões de Mangá (Distribuição)**](https://github.com/Awerkori/manga-extensoes)

---

## 🎯 Sobre o Projeto

O ecossistema de mangás do Project Nox mantém fontes atualizadas com foco na comunidade lusófona (PT-BR) e fontes selecionadas. Este repositório gerencia o ciclo de desenvolvimento das extensões:

- **Desenvolvimento e Manutenção:** Código em Kotlin estruturado sobre a arquitetura Keiyoushi / Tachiyomi Extension API.
- **Pipeline de Integração Contínua (CI):** Compilação automatizada via Gradle e GitHub Actions, gerando APKs assinados e índices Protobuf/JSON.
- **Publicação Automática:** Cada alteração aprovada na branch principal compila e publica os artefatos diretamente no repositório de distribuição [`manga-extensoes`](https://github.com/Awerkori/manga-extensoes).

---

## 🏗️ Estrutura do Repositório

- `src/`: Extensões separadas por idioma (ex: `src/pt/` para fontes em português). Cada subpasta representa uma fonte ou multisource.
- `lib/`: Bibliotecas compartilhadas e utilitários de rede, parsers e seletores.
- `core/`: Utilitários centrais de catalogação e testes unitários.
- `.github/workflows/`: Workflows de automação (`build_push.yml`) responsáveis por compilar, testar e publicar os pacotes.
- `.github/scripts/`: Scripts auxiliares em Python para geração do catálogo e purga de cache CDN.

---

## 💻 Como Compilar Localmente

### Pré-requisitos
- **JDK 17** ou superior instalado e configurado no `JAVA_HOME`.
- **Android SDK** com Build-Tools recentes e plataformas Android 30+.

### Clonar o repositório
```bash
git clone https://github.com/Awerkori/manga-fonte-extensoes.git
cd manga-fonte-extensoes
```

### Compilar uma extensão específica
Para compilar uma extensão (por exemplo, dentro de `src/pt/`):
```bash
# Compilar o APK da extensão em modo debug
./gradlew :src:pt:<nome-da-extensao>:assembleDebug
```

Os APKs gerados ficam localizados em `src/pt/<nome-da-extensao>/build/outputs/apk/debug/`.

---

## 🤝 Como Contribuir

Contribuições da comunidade são muito bem-vindas:

1. **Abra ou consulte uma solicitação:** Antes de iniciar uma grande refatoração ou criar uma nova fonte, verifique a [Central de Pedidos](https://github.com/Awerkori/project-nox-requests) para evitar esforços duplicados.
2. **Crie uma branch:** Nomeie de forma semântica (ex: `fix/nome-da-fonte` ou `feat/nova-fonte`).
3. **Mantenha os padrões:** Utilize os seletores mais estáveis possíveis, evite hardcode desnecessário e garanta que os testes passem.
4. **Envie um Pull Request:** Descreva com clareza o que foi corrigido ou adicionado.

---

## 🔗 Projetos Relacionados

- 📦 [**Project Nox · Extensões de Mangá**](https://github.com/Awerkori/manga-extensoes) — Repositório de distribuição (APKs compilados e catálogo `index.pb`).
- 🎬 [**Project Nox · Extensões de Anime**](https://github.com/Awerkori/anime-extensoes) — Catálogo de extensões de vídeo para Aniyomi.
- 💻 [**Project Nox · Código-fonte das Extensões de Anime**](https://github.com/Awerkori/anime-fonte-extensoes) — Fontes e extractors de vídeo.
- 🎫 [**Project Nox · Central de Pedidos**](https://github.com/Awerkori/project-nox-requests) — Reporte de fontes quebradas e solicitações.
- 📊 [**Project Nox · Tracker**](https://awerkori.github.io/project-nox-tracker/) — Painel de acompanhamento de status.
- 🌐 [**Project Nox · Site**](https://manga.project-nox-awerkori.workers.dev) — Leitor web oficial.

---

## ⚖️ Licença e Créditos

Distribuído sob a licença **Apache 2.0**. Consulte o arquivo [LICENSE](LICENSE) para obter mais informações.

```
Copyright 2015 Javier Tomás
Copyright (c) Keiyoushi Contributors & Project Nox Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

### Isenção de Responsabilidade
O **Project Nox** desenvolve e mantém ferramentas e scripts para interoperabilidade de dados abertos. Não possuímos controle nem direitos sobre os conteúdos fornecidos por sites de terceiros acessados pelas extensões. Este projeto não possui afiliação oficial com a equipe de desenvolvimento do Mihon ou Tachiyomi.

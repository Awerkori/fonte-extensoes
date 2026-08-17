# Contribuindo com o Project Nox

Obrigado pelo interesse em contribuir com o **Project Nox**.

Este repositório contém o código-fonte das extensões mantidas pelo projeto para Mihon e forks compatíveis.

- **Código-fonte:** https://github.com/Awerkori/fonte-extensoes
- **Repositório de extensões:** https://github.com/Awerkori/extensoes
- **Discord:** https://discord.gg/QpyjwsWENq
- **Fluxer:** https://fluxer.gg/q456UCVt

---

## Pedidos de novas extensões e correções

Se você apenas quer:

- pedir uma nova extensão;
- avisar que uma fonte parou de funcionar;
- solicitar atualização de domínio;
- informar capítulos ou imagens quebradas;
- pedir uma melhoria em uma extensão existente;

**não é necessário abrir um Pull Request.**

Faça o pedido pela comunidade do Project Nox:

**Discord**  
https://discord.gg/QpyjwsWENq

**Fluxer**  
https://fluxer.gg/q456UCVt

Antes de pedir uma nova extensão, verifique se ela já existe no **Project Nox** ou no **Keiyoushi**.

> Um pedido não garante que uma fonte será adicionada ou corrigida imediatamente. Algumas fontes podem ser difíceis ou inviáveis de manter.

---

## Contribuições de código

Pull Requests são bem-vindos.

Você pode contribuir com:

- novas extensões;
- correções de extensões existentes;
- atualização de domínio;
- correção de Browse, Latest ou Search;
- correção de Details, Chapters ou Reader;
- filtros;
- melhorias de compatibilidade;
- correções em libs ou multisrc;
- melhorias de infraestrutura, quando realmente necessárias.

---

## Antes de começar

Leia as regras atuais do repositório:

- `CONTRIBUTING.md`
- `.github/rules/Extension_Guide.md`

Também é recomendado consultar implementações existentes antes de criar algo do zero.

O Project Nox acompanha de perto a arquitetura do **Keiyoushi**, portanto alterações devem continuar compatíveis com o ecossistema sempre que possível.

---

## Extensões novas

Antes de criar uma fonte, confirme que ela ainda não existe em:

- Project Nox;
- Keiyoushi;
- multisrc existente;
- implementação anterior com outro nome ou domínio.

Não crie uma extensão duplicada apenas porque um site mudou de nome ou endereço.

Quando tecnicamente correto, migrações devem preservar a identidade da fonte existente.

---

## Correções

Uma extensão existente deve ser **consertada**, não reescrita sem necessidade.

Se algo já funciona:

**preserve.**

Se uma versão anterior funcionava:

**use o histórico Git como referência.**

Evite:

- refatoração apenas por estética;
- troca desnecessária de API/parser;
- fallbacks excessivos;
- retries cegos;
- esconder erros usando `emptyList()`;
- mudanças fora do escopo do problema.

---

## Testes

Um build concluído com sucesso não significa que a extensão funciona.

Quando aplicável, teste:

- Popular / Browse;
- Latest;
- Search;
- filtros;
- Details;
- Chapters;
- paginação;
- Reader / PageList;
- imagens.

Para capítulos, prefira testar uma obra curta, uma média e uma longa.

Para leitura, teste capítulos antigos e recentes.

---

## Versionamento

Não aumente `versionCode` aleatoriamente.

Antes de publicar uma alteração, considere:

- versão atualmente publicada pelo Project Nox;
- versão equivalente no Keiyoushi;
- arquitetura/libVersion da extensão.

Builds DEBUG ou APKs locais **não consomem versões públicas**.

O versionamento final será revisado antes da publicação.

---

## Sync com Keiyoushi

O Project Nox sincroniza automaticamente alterações do Keiyoushi.

A regra atual é:

- somente Keiyoushi alterou → aplica Keiyoushi;
- somente Nox alterou → preserva Nox;
- ambos alteraram a mesma unidade → **Nox prevalece**.

Em um conflito, o código Nox é preservado e o `versionCode` é ajustado automaticamente quando necessário.

Não adicione:

- listas manuais de proteção;
- `nox-protected.txt`;
- locks;
- force push;

sem uma necessidade comprovada.

---

## Escopo

Mantenha cada contribuição focada.

Uma correção de extensão não deve aproveitar para:

- alterar outras fontes;
- refatorar libs globais;
- modificar workflows;
- reorganizar o repositório;
- atualizar dependências sem relação com o problema.

Se encontrar outro problema, trate-o separadamente.

---

## Segurança

Nunca envie para o repositório:

- tokens;
- PATs;
- webhooks;
- senhas;
- arquivos `.env`;
- JKS;
- chaves privadas;
- credenciais.

Se algum segredo for exposto acidentalmente, considere-o comprometido e revogue-o.

---

## Pull Requests

Antes de enviar um Pull Request:

- mantenha a alteração focada;
- confirme que o código compila;
- execute os testes relevantes;
- rode `git diff --check`;
- não inclua arquivos temporários;
- não inclua APKs;
- não inclua credenciais;
- explique de forma curta o problema e a solução.

Para correções, informe o que estava quebrado e como foi validado.

Para novas extensões, informe o site e as funções que foram testadas.

---

## Infraestrutura

Mudanças em:

- `.github/workflows/`;
- `.github/scripts/`;
- `core/`;
- `compiler/`;
- `gradle/`;
- sistema de publicação;
- sistema de sincronização;

devem ser feitas somente quando houver necessidade técnica real.

A infraestrutura do Project Nox possui adaptações próprias e não deve ser substituída integralmente pela infraestrutura do Keiyoushi ou de outro fork.

---

## Créditos

O Project Nox é construído sobre o trabalho da comunidade de extensões do Mihon/Tachiyomi.

Agradecimentos especiais ao:

### Keiyoushi

https://github.com/keiyoushi/extensions-source

Grande parte da base técnica e das extensões utilizadas pelo Project Nox é derivada do trabalho dos mantenedores e contribuidores do Keiyoushi.

### FelipeGFA

https://github.com/FelipeGFA/fonte-extensoes

Parte da infraestrutura e da arquitetura inicial utilizada pelo Project Nox foi baseada no trabalho do FelipeGFA.

---

## Licença

Ao contribuir com este repositório, suas contribuições ficam sujeitas à licença aplicável ao projeto e aos arquivos modificados.

Consulte o arquivo:

`LICENSE`

Este repositório utiliza a **Apache License 2.0** para o código coberto por ela.

---

## Comunidade

Para conversar sobre desenvolvimento, pedir ajuda ou discutir uma contribuição:

**Discord:**  
https://discord.gg/QpyjwsWENq

**Fluxer:**  
https://fluxer.gg/q456UCVt

---

<p align="center">
  <b>Project Nox</b><br>
  Contribuições são bem-vindas.
</p>

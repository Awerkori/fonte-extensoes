# XXX Yaoi — validação local (2026-09-13)

```sh
./gradlew :src:pt:xxxyaoi:testDebugUnitTest :src:pt:xxxyaoi:assembleDebug :src:pt:xxxyaoi:lintRelease
```

- 67 testes JVM, zero falhas; fixtures sintéticas do mecanismo XOR/Base64 e da matriz de bytes observada no site ao vivo, variações de JS/atributos, JSON, HTML/lazy-load, normalização, ordem, payload longo, exclusões de cache com curingas e erros.
- JS externo é testado com downloads simulados (incluindo script após 24 arquivos, falha HTTP, divisão de declarações e cancelamento); o site atual usa JS inline.
- O teste do fallback Madara verifica o contrato do callback; não é uma execução do protector AES real.
- Build debug e lint aprovados (dois avisos no manifesto gerado: AppLinkWarning e DataExtractionRules).
- APK: `build/outputs/apk/debug/tachiyomi-pt.xxxyaoi-v1.6.61.apk`.
- Fonte: ID público preservado, `2490962516027621137`.
- Versões: pública Nox anterior `1.4.59`; local anterior `7 + 52 = 59`; upstream `4 + 54 = 58`; candidata `7 + 54 = 61`, Android versionCode `106061`. Sem bump do versionCode bruto.
- Sync: `get_protected_nox_units` já inclui `src/pt/xxxyaoi`; mecanismo padrão preserva fonte Nox e atualiza theme/libVersion com verificação de compilação. Sem lista manual.

## Validação real no Mihon / Waydroid

- Catálogo, busca `He Might Bite`, detalhes e lista de 36 capítulos funcionaram.
- Reproduzido o erro real: o site constrói a chave XOR por um array numérico; sem reconhecê-lo, o fallback anterior aceitava sete curingas de configuração de cache como URLs de páginas, gerando HTTP 404.
- Corrigido: arrays de bytes decimais/hexadecimais também fornecem chaves candidatas, sem depender de nomes de variáveis. Curingas e diretórios vazios não são imagens.
- He Might Bite!: capítulos 1 (antigo, 16 páginas), 2 (15), 34 (16), 35 (18) e 36 (recente, 16) abriram no reader. Imagens solicitadas retornaram HTTP 200; ordem mantida conforme payload. Navegação página 1 → 2 e capítulos 34 → 35 → 36 confirmadas.
- Nova rodada: busca Love Jinx, 80 capítulos, bônus 04 com 7 imagens, HTTP 200 e rolagem vertical confirmada. He Might Bite! teve a lista atualizada e os capítulos 1, 2 e 36 abertos novamente.
- Fixtures `test/fixtures/live-chapter.html` e `live-properties.html` vêm de trechos reais sanitizados, com testes removendo todas as classes. Datas e nomes são identificados pelo conteúdo; `EM HIATO` é reconhecido. Datas sem fuso usam meia-noite local para não aparecerem no dia anterior.
- URLs reais dos capítulos ficam no memo, preservando o slug como identidade e os parâmetros necessários. Links relativos do AJAX são resolvidos contra o caminho da obra.
- Reader ignora comentários de JS, aceita objetos literais e vírgulas finais sem executar código, prioriza listas explícitas de páginas, rejeita rotas/coringas/configurações e falha descritivamente se houver empate entre listas diferentes. JS externo é buscado apenas quando a extração local falha (até 64 scripts declarados, 512 KB por arquivo, 2 MB no conjunto).
- Logs temporários usados para comparar URLs e status HTTP foram removidos do APK final.
- Sem NPE ou 404 nos capítulos testados após a correção. Mudanças arbitrárias futuras do site não são cobertas por essa garantia.

Validação final para publicação do lote XXX Yaoi + Manga NXY.

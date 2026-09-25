# XXX Yaoi — contratos do reader

## Mecanismo confirmado no aparelho

O HTML HTTP 200 contém payload em atributo `data-d`, chave em propriedade CSS e o decodificador do site. O RC4/text-template pode coexistir como isca que retorna `warning_app.jpg`. O reader CSS tem prioridade; falha nele não autoriza usar a isca, procurar chaves por tentativa ou baixar scripts aleatórios.

O usuário confirmou leitura funcional no Komikku após corrigir o fechamento da regex para Android. A rodada de endurecimento precisa de nova validação manual; testes locais não substituem GETs reais do site.

## Invariantes

- Nomes de variáveis/funções não identificam o reader novo. ID, seletor, atributo, propriedade CSS, seed e shift são extraídos do script de cada capítulo.
- Aceitar espaços/quebras, aspas simples/duplas, parâmetros decimais/hexadecimais, comentários entre parâmetros, CSS :root/html e !important.
- Só aceitar uma combinação não ambígua. CSS condicional/herança/expressões dinâmicas não suportadas exigem manutenção; nunca escolher uma chave aleatória.
- Payload completo, ordem original, deduplicação e query de imagens assinadas preservados. Nenhuma página inválida pode ser descartada silenciosamente no reader novo.
- Nenhuma chave/payload/URL de capítulo é armazenada globalmente. Capítulos concorrentes são testados.
- `warning_app.jpg` em qualquer posição é bloqueio, não página. Capas/thumbs não substituem páginas.
- `Page.url` é a URL do capítulo. GET de imagem usa somente User-Agent, Accept image/* e Referer; CookieJar do cliente permanece responsável pelos cookies.
- Sem WebView adicional, hooks fetch/XHR, metadados de headers no Page.url ou logs de credenciais.
- Limite de 4 milhões de caracteres no payload para proteger memória; não é limite de páginas.

## Verificação automática

### Regressão confirmada em 23/09/2026: data-xsec

Revisão adicional: scripts AES/CSS compartilhados só selecionam esses leitores quando o contêiner referenciado existe no documento. Testes verificam coexistência com `data-xsec` e scripts CSS inativos sem ambiguidade. Cancelamento no fallback Madara é propagado, sem iniciar buscas de scripts após sair do leitor. A suíte AES inicializa a dependência JSON antes dos testes e cobre também saída JSON descriptografada.

Love Jinx / capitulo-bonus-04 retornou HTTP 200 tanto no OkHttp do Komikku quanto no Firefox do aparelho. O HTML original traz `img[data-xsec]` com URL invertida e `src` SVG vazio; o JavaScript oficial aplica `split("").reverse().join("")`. O parser antigo ignorava essas URLs, buscava scripts externos e terminava em `payload-invalid`. Isso é distinto dos HTTP 403 observados no catálogo/curl.

A correção decodifica apenas `data-xsec` antes de validar a URL e mantém os headers e CookieJar existentes. Testes cobrem ordem, query, entidades HTML, URLs relativas, páginas mistas, classes CSS diferentes, esquemas inválidos e ausência de requests adicionais de scripts. No aparelho, Bônus 04 e Bônus 03 entregaram 7/7 imagens HTTP 200 cada; o HTML do Firefox também informa sete páginas por capítulo. Os 102 testes locais passaram, junto com lintRelease e assembleDebug. Reiniciar o app após instalar APK de mesma versão é parte da validação.

```sh
./gradlew :src:pt:xxxyaoi:assembleRelease
```

O assembleRelease depende de testDebugUnitTest, spotlessCheck e lintRelease. Assim, o CI existente falha antes de publicar quando esses checks falham. Não foram alteradas as regras de matriz, push ou publicação. A fonte já consta em .github/nox-protected.txt.

A suíte é determinística e não usa internet/proxies públicos. Inclui um servidor HTTP local para verificar GET, corpo PNG preservado, cookies, Referer, headers proibidos e URLs distintas. Esse teste NÃO prova funcionamento do CDN real.

## Regex no Android (obrigatório após mudanças nos padrões)

O JDK aceitou uma regex que o Android ICU rejeitou, fechando ReaderActivity com ExceptionInInitializerError. Este check compila os padrões reais do arquivo CssReader.kt no dispositivo:

```sh
python3 src/pt/xxxyaoi/tools/check_android_regex.py --serial SERIAL_DO_ADB
```

Requer JDK, SDK local e adb. Usa um DEX temporário em /data/local/tmp e remove somente esse artefato; não modifica dados de aplicativos. Os parâmetros interpolados usam valores de teste. A validação não executa o parser completo no Android.

## Teste real antes de publicar

Reiniciar o Komikku após atualizar APK de mesma versão. Abrir capítulos novos: Aporia (3), Shell Boy (3), outra obra (1). Confirmar ordem e quantidade plausível, warning_app=0, primeiro/meio/último GET de imagem 200, image/* e bytes > 0. Revalidar catálogo/busca/detalhes/capítulos. Não considerar cache antigo ou testes sintéticos como prova do CDN.

Não há garantia contra mudanças arbitrárias do site. Quando o formato sair do contrato, manter erro explícito em vez de devolver páginas falsas.

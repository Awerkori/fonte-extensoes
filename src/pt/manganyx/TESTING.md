# Manga NXY — correção local

Causa observada em 2026-09-13:
- `manganyx.com` responde 301 para `manganyx.org`.
- O redirecionamento converte o POST `/api/gate/start` em GET: HTTP 405.
- No domínio final, o POST sem `Origin` recebe 403: `Origem nao autorizada`.

Correção somente nesta fonte: domínio atual e interceptor que acompanha
redirecionamentos de documentos, ajusta Origin/Referer e preserva POST,
caminho e query. Reutiliza integralmente o reader Aurora existente, com
RSC, HTML/Flight, gate, descriptografia e normalização. Não altera Aurora.

Validação:
- 3 testes JVM: domínio migrado, Origin, método POST, chave/query,
  Referer, CDN independente e rejeição de redirects inadequados.
- `:src:pt:manganyx:testDebugUnitTest`, `assembleDebug`, `lintRelease`: PASS.
- Mihon/Waydroid: catálogo, busca One Piece, detalhes e capítulos.
- One Piece 1193: 16 páginas; 1192: 14 páginas; 1: 53 páginas.
- Primeiras/últimas imagens HTTP 200; navegação e rolagem verificadas.
- URLs públicas: `/manga/one-piece/1193`, `/1192`, `/1` no mesmo domínio.

Versão final: raw 1 + Aurora 4 = 1.6.5; pública anterior 1.6.4.
O sync padrão preserva alterações Nox em `src/` após inclusão no commit;
não exige lista específica nem impede atualização estrutural da Aurora.

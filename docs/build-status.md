# Estado da implementação e verificação

Atualizado em 2026-10-26.

## Implementado

- Projeto Android nativo com interface Compose, cliente Shizuku e motor experimental de políticas de áudio por UID.
- Dois aplicativos auxiliares de teste, com sons distintos e execução em segundo plano.
- Restauração transacional, monitoramento das conexões e exportação local do diagnóstico.
- Testes automatizados para transações, ciclo de vida, diagnóstico, pré-verificação e protocolo do atualizador.
- Atualizações pelo GitHub com assinatura fixa e instalação confirmada pelo Android; publicação depende da configuração do secret do mantenedor.
- Gradle Wrapper oficial, workflow de CI e empacotamento dos três APKs com checksums.

## Verificado localmente

- XML dos manifests e recursos válido.
- Sintaxe dos scripts shell e Python válida.
- JAR do Gradle Wrapper íntegro e com SHA-256 correspondente à referência oficial da versão 8.11.1.
- Scripts do Wrapper correspondentes ao conteúdo oficial.
- Revisão estática das assinaturas AIDL, acesso às APIs internas e tratamento de falhas.

## Verificado no GitHub Actions

A [execução 40249397916](https://github.com/GabrielRbro/audio-split/actions/runs/40249397916), commit `294b3419d440d992fb67af303ad4d7fa1d5fcfbe` (0.1.1), concluiu com sucesso:

- Testes `:core:test` e `:app:testDebugUnitTest`.
- Lint do aplicativo principal e das duas variantes auxiliares.
- Compilação dos três APKs debug.
- Publicação dos artefatos de desenvolvimento e dos relatórios.
- 25 testes sem falhas, erros ou casos ignorados e sonda real de atribuição shell no emulador Android 16.

O pacote de distribuição inclui `TEST-RESULTS.json`, com a contagem extraída dos relatórios JUnit da própria compilação, e `SHA256SUMS.txt`, com os checksums dos três APKs. O empacotamento exige testes descobertos nos dois módulos e ausência de falhas, erros ou casos ignorados.

## Pendente

- Validação de uma atualização real pelo instalador Android no aparelho, mantendo a assinatura da release 0.2.0.
- Correção da falha física relatada no POCO F7: iniciar o Spotify leva o áudio do Instagram para o Bluetooth. O novo diagnóstico não equivale a uma correção comprovada.

Compilação e testes automatizados aprovados não comprovam a separação física das saídas. O teste da 0.1.1 não atendeu ao objetivo de saídas exclusivas; a compatibilidade do aparelho continua sem confirmação.

## Correção 0.1.1

O teste físico da 0.1.0 revelou que a pré-verificação rejeitava o contexto de áudio antes de registrar qualquer política. No Android 16, `createPackageContext()` herda os nomes de pacote do contexto anterior. A versão 0.1.1 cria um contexto independente para o pacote shell e confere separadamente o UID real do processo e sua permissão efetiva.

O workflow da correção exige uma sonda em emulador Android 16, executada como UID shell 2000. A sonda precisa reproduzir a rejeição da versão anterior, validar a atribuição corrigida e ler os dispositivos de áudio. Ela não registra políticas nem comprova Bluetooth. O artefato só é publicado após essa etapa passar; o relatório aparece em `app/build/reports/shell-context-probe.txt`.

O serviço Shizuku também recebe a nova versão do build para substituir seu processo anterior, e a interface só habilita aplicar quando o diagnóstico está aprovado. O arquivo `TEST-RESULTS.json` do pacote informa os resultados reais da compilação correspondente.

## Versão 0.2.0

A [execução 40403172420](https://github.com/GabrielRbro/audio-split/actions/runs/40403172420), commit `efe4267a2632638b1810d86fd50ae47a2aea620f`, passou em todos os checks de desenvolvimento: 48 testes, zero falhas, erros ou casos ignorados; lints e compilação dos três APKs; sonda shell no Android 16; abertura real da tela sem Shizuku e verificação do FileProvider de atualização instalado.

O job de release não compilou nem publicou o APK assinado porque o secret `ANDROID_SIGNING_CONFIG` ainda não estava configurado. O resultado verde dessa execução não significa que uma release pública esteja disponível. A instalação real de uma atualização pelo próprio aplicativo também continua pendente de validação.

Os 23 testes novos cobrem protocolo/integridade/URLs do atualizador e detecção de divergência persistente das rotas. O emulador não comprova o roteamento Bluetooth do POCO.

## Publicação assinada 0.2.0

Após a configuração do secret, a [execução 40406817432](https://github.com/GabrielRbro/audio-split/actions/runs/40406817432), commit `47c3bdd1c3ea45ae1e9171c7a124e57306f4cc14`, concluiu os 48 testes e as regressões Android 16, compilou o APK de release e verificou seu pacote, versão e certificado fixo com `apksigner` antes de publicar.

A [release pública v0.2.0](https://github.com/GabrielRbro/audio-split/releases/tag/v0.2.0) contém o APK principal, `update.json` e `SHA256SUMS.txt`. O APK tem código de versão 3 e SHA256 `7b54b64c48abc4ca50930d89dcbb1a9aded2d82f5b4a8cfe1d090c3b88d0e4cc`, também informado pelo digest do asset no GitHub. A assinatura estável está ativa; a instalação pelo atualizador e a correção do roteamento no POCO continuam dependendo de validação física.

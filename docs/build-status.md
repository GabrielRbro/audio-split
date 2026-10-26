# Estado da implementação e verificação

Atualizado em 2026-10-26.

## Implementado

- Projeto Android nativo com interface Compose, cliente Shizuku e motor experimental de políticas de áudio por UID.
- Dois aplicativos auxiliares de teste, com sons distintos e execução em segundo plano.
- Restauração transacional, monitoramento das conexões e exportação local do diagnóstico.
- 25 casos de teste automatizados escritos para transações, ciclo de vida, diagnóstico e bloqueio de aplicação após pré-verificação negada.
- Gradle Wrapper oficial, workflow de CI e empacotamento dos três APKs com checksums.

## Verificado localmente

- XML dos manifests e recursos válido.
- Sintaxe dos scripts shell e Python válida.
- JAR do Gradle Wrapper íntegro e com SHA-256 correspondente à referência oficial da versão 8.11.1.
- Scripts do Wrapper correspondentes ao conteúdo oficial.
- Revisão estática das assinaturas AIDL, acesso às APIs internas e tratamento de falhas.

## Verificado no GitHub Actions

A [execução 40246800048](https://github.com/GabrielRbro/audio-split/actions/runs/40246800048), commit `18afe81e51b5702597a4583f698a7e5b887cbc9b`, concluiu com sucesso:

- Testes `:core:test` e `:app:testDebugUnitTest`.
- Lint do aplicativo principal e das duas variantes auxiliares.
- Compilação dos três APKs debug.
- Publicação dos artefatos de desenvolvimento e dos relatórios.

O pacote de distribuição inclui `TEST-RESULTS.json`, com a contagem extraída dos relatórios JUnit da própria compilação, e `SHA256SUMS.txt`, com os checksums dos três APKs. O empacotamento exige testes descobertos nos dois módulos e ausência de falhas, erros ou casos ignorados.

## Pendente

- Testes físicos do roteamento e da reprodução simultânea no POCO F7, conforme [protocolo](test-protocol.md).

Compilação e testes automatizados aprovados não comprovam a separação física das saídas nem a reprodução simultânea de Spotify e Instagram. A compatibilidade com o aparelho continua sem confirmação até executar o protocolo físico.

## Correção 0.1.1

O teste físico da 0.1.0 revelou que a pré-verificação rejeitava o contexto de áudio antes de registrar qualquer política. No Android 16, `createPackageContext()` herda os nomes de pacote do contexto anterior. A versão 0.1.1 cria um contexto independente para o pacote shell e confere separadamente o UID real do processo e sua permissão efetiva.

O workflow da correção exige uma sonda em emulador Android 16, executada como UID shell 2000. A sonda precisa reproduzir a rejeição da versão anterior, validar a atribuição corrigida e ler os dispositivos de áudio. Ela não registra políticas nem comprova Bluetooth. O artefato só é publicado após essa etapa passar; o relatório aparece em `app/build/reports/shell-context-probe.txt`.

O serviço Shizuku também recebe a nova versão do build para substituir seu processo anterior, e a interface só habilita aplicar quando o diagnóstico está aprovado. O arquivo `TEST-RESULTS.json` do pacote informa os resultados reais da compilação correspondente.

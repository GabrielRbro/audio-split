# Estado da implementação e verificação

Atualizado em 2026-10-25.

## Implementado

- Projeto Android nativo com interface Compose, cliente Shizuku e motor experimental de políticas de áudio por UID.
- Dois aplicativos auxiliares de teste, com sons distintos e execução em segundo plano.
- Restauração transacional, monitoramento das conexões e exportação local do diagnóstico.
- 18 casos de teste automatizados escritos para transações, ciclo de vida e contrato de diagnóstico.
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

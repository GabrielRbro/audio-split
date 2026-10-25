# Estado da implementação e verificação

Atualizado em 2026-10-25.

## Implementado

- Projeto Android nativo com interface Compose, cliente Shizuku e motor experimental de políticas de áudio por UID.
- Dois aplicativos auxiliares de teste, com sons distintos e execução em segundo plano.
- Restauração transacional, monitoramento das conexões e exportação local do diagnóstico.
- 18 casos de teste automatizados escritos para transações, ciclo de vida e contrato de diagnóstico.
- Gradle Wrapper oficial, workflow de CI e empacotamento dos três APKs com checksums.

## Verificado neste ambiente

- XML dos manifests e recursos válido.
- Sintaxe dos scripts shell e Python válida.
- JAR do Gradle Wrapper íntegro e com SHA-256 correspondente à referência oficial da versão 8.11.1.
- Scripts do Wrapper correspondentes ao conteúdo oficial.
- Revisão estática das assinaturas AIDL, acesso às APIs internas e tratamento de falhas.

## Pendente

- Compilação dos APKs, lint Android e execução dos 18 testes: o ambiente ainda precisa baixar o SDK Android, o Gradle e as dependências. As tentativas de obter acesso adicional de rede ficaram aguardando permissão e foram interrompidas; nenhuma rejeição automática foi informada.
- Testes físicos do roteamento e da reprodução simultânea no POCO F7, conforme [protocolo](test-protocol.md).

Os arquivos de código-fonte não equivalem a um APK compilado ou a uma confirmação de compatibilidade com o aparelho. Atualizar este documento com os resultados reais após executar `scripts/check-and-build.sh`.

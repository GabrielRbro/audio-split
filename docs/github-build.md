# Gerar os APKs pelo GitHub Actions

Este caminho compila em um runner do GitHub com JDK e Android SDK. Ele não depende de instalar ferramentas no ambiente onde o código foi preparado.

## Preparar o repositório

1. Crie um repositório privado e vazio chamado `audio-split` na sua conta GitHub. Não adicione README, licença ou `.gitignore` nessa criação; esses arquivos já estão no projeto.
2. Dê à conexão GitHub desta conversa acesso a esse repositório, caso ela esteja limitada a repositórios selecionados.
3. Envie o link do repositório nesta conversa para o envio do código e acompanhamento da compilação.

Como alternativa manual, extraia o ZIP de código-fonte e envie o conteúdo da pasta `audio-split` para a raiz do repositório. Inclua a pasta oculta `.github`, os scripts `gradlew` e `gradlew.bat` e `gradle/wrapper/gradle-wrapper.jar`.

## Acompanhar e baixar

O primeiro envio dispara o workflow **Android checks and debug APKs**. Ele executa os testes de sessão e de protocolo, o lint dos três aplicativos e a montagem dos três APKs.

Depois da execução, abra **Actions → Android checks and debug APKs → execução concluída**. O artefato `audio-split-debug` contém os APKs; `android-check-reports` contém os relatórios que estiverem disponíveis. Os artefatos são mantidos por 14 dias e podem ser gerados novamente.

| APK | Finalidade |
| --- | --- |
| `audio-split-0.1.0-debug.apk` | Aplicativo Audio Split com Shizuku |
| `tom-a-bluetooth-0.1.0-debug.apk` | Tom A, destino desejado Bluetooth |
| `tom-b-celular-0.1.0-debug.apk` | Tom B, destino desejado alto-falante |

O mesmo artefato inclui instruções e `SHA256SUMS.txt`, com os checksums dos três APKs.

O arquivo `TEST-RESULTS.json` contém o resultado dos testes extraído dos relatórios JUnit dessa compilação. A etapa de empacotamento também imprime a contagem no log e recusa relatórios vazios, falhas, erros ou casos ignorados.

Uma execução com falha precisa ser corrigida antes de considerar os APKs prontos. Uma execução com sucesso comprova a compilação e os checks automatizados; o comportamento físico continua dependendo do [teste no POCO F7](test-protocol.md).

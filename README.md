# Audio Split — protótipo Android

Aplicativo experimental para investigar a separação do áudio de dois aplicativos entre uma caixa Bluetooth e o alto-falante do celular, usando Shizuku e sem root.

**Estado: não testado no aparelho físico.** O primeiro alvo é o POCO F7 com Android 16 e HyperOS 3.0.303.0.WOLMIXM.C06. A existência de permissões ou o registro de uma política de áudio não comprovam que as duas saídas funcionam simultaneamente. Consulte o [registro de compatibilidade](docs/compatibility.md).

O código está implementado; a geração dos APKs e a execução dos testes ainda dependem da instalação do toolchain Android. Veja o [estado da verificação](docs/build-status.md).

Também é possível [gerar os APKs pelo GitHub Actions](docs/github-build.md), usando um repositório privado.

O objetivo do teste é:

| Aplicativo | Saída desejada |
| --- | --- |
| Spotify (`com.spotify.music`) | Caixa Bluetooth selecionada |
| Instagram (`com.instagram.android`) | Alto-falante do celular |

Os dois precisam tocar ao mesmo tempo por cinco minutos, sem pausa ou redução de volume causada pela abertura do outro aplicativo, e cada som deve sair apenas no destino escolhido.

## O que está incluído

- APK principal em Kotlin e Jetpack Compose, com diagnóstico, aplicação da separação, restauração e exportação do relatório.
- Seletor entre aplicativos reais e fontes auxiliares de teste, além da seleção de uma saída Bluetooth conectada.
- Dois APKs auxiliares com sons distintos, para testar regras por aplicativo antes de usar Spotify e Instagram.
- Motor executado como shell por um `UserService` do Shizuku. As APIs internas de áudio ficam isoladas nesse motor.
- Testes do gerenciamento de sessão e da restauração em caso de falha parcial.

O processamento ocorre localmente. O projeto não inclui captura nem retransmissão do áudio dos aplicativos. As fontes auxiliares não escolhem a própria saída: o destino efetivo depende das regras aplicadas pelo APK principal.

## Preparar o celular

1. Instale o [Shizuku oficial 13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0), ou versão posterior compatível com Android 16.
2. Ative as opções de desenvolvedor e a depuração sem fio do Android. Faça o pareamento e inicie o Shizuku conforme o [manual oficial](https://shizuku.rikka.app/guide/setup/). O pareamento usa uma rede Wi-Fi; o aplicativo não precisa de um servidor remoto para rotear áudio.
3. Instale o APK principal. Abra-o e autorize seu acesso no Shizuku quando solicitado.
4. Conecte a caixa Bluetooth e confirme que ela reproduz mídia normalmente antes do teste.
5. Execute **Diagnosticar**. Confira se o Shizuku está disponível, se os aplicativos foram encontrados e se a caixa aparece na seleção.
6. No HyperOS, procure em **Configurações → Som e vibração** a opção de múltiplas fontes de áudio ou Assistente de som. Se disponível, ative a reprodução simultânea manualmente. O nome e o caminho podem variar; essa opção não comprova a separação das saídas.

O manual do Shizuku informa que alguns Xiaomi/POCO podem precisar da opção adicional **Depuração USB (configurações de segurança)**. Verifique-a apenas se a opção existir e o diagnóstico indicar privilégios limitados. Ela é diferente da depuração USB comum; não altere outras opções de desenvolvedor para tentar contornar um erro.

Após reiniciar o celular, confira novamente o estado do Shizuku. As rotas do protótipo precisam ser reaplicadas explicitamente.

## Executar o teste

Comece pelos aplicativos auxiliares. Depois repita com os aplicativos oficiais no perfil principal do Android.

1. No APK principal, selecione o modo de teste e a caixa conectada.
2. Toque em **Aplicar separação**. Um estado de configuração aplicada ainda precisa de confirmação auditiva.
3. Inicie o som do aplicativo destinado ao Bluetooth e depois o som do destinado ao telefone.
4. Confirme a saída de cada um e siga o [protocolo completo](docs/test-protocol.md), inclusive iniciando na ordem inversa.
5. Ao terminar, toque em **Restaurar áudio padrão** e verifique se a reprodução voltou ao comportamento normal do Android.
6. Use **Exportar diagnóstico** para salvar o resultado técnico e registre suas observações no relatório de compatibilidade.

Alternar para Spotify, Instagram ou a tela bloqueada faz parte do teste. Voltar para a tela inicial não equivale a solicitar a restauração. Use o botão de restauração para encerrar o teste; a perda da sessão privilegiada também deve desmontar as políticas criadas pelo protótipo. Desconectar o Bluetooth encerra a separação e exige nova aplicação após reconectar.

Se um aplicativo pausar, ficar mudo ou reduzir seu volume quando o outro começa, registre a ocorrência. O Android gerencia o [foco de áudio](https://developer.android.com/media/optimize/audio-focus) separadamente do roteamento. Um resultado parcial não atende ao objetivo completo e não demonstra impossibilidade em todos os aparelhos.

## Compilar e verificar

Requisitos: JDK 17 ou superior compatível com Gradle 8.11.1, Android SDK com a plataforma API 36 e aceitação das licenças do SDK. O projeto usa Android Gradle Plugin 8.10.1 e Kotlin 2.1.20. Configure o caminho do SDK em `local.properties` (`sdk.dir=...`) ou no ambiente do Android SDK; não inclua esse arquivo em commits.

```sh
./gradlew :core:test :app:lintDebug
./gradlew :app:assembleDebug :tone-player:assembleBluetoothDebug :tone-player:assemblePhoneDebug
```

Para executar todos os checks e gerar os três APKs no Linux/macOS, use `./scripts/check-and-build.sh`. Depois, `python3 scripts/package-debug.py` reúne os APKs, instruções e checksums em `dist/audio-split-0.1.0-debug.zip`. O workflow em `.github/workflows/android.yml` executa as mesmas verificações quando o projeto for publicado.

No Windows, use `gradlew.bat` no lugar de `./gradlew`.

| Artefato de desenvolvimento | Caminho após compilação |
| --- | --- |
| Aplicativo principal | `app/build/outputs/apk/debug/app-debug.apk` |
| Fonte auxiliar Bluetooth | `tone-player/build/outputs/apk/bluetooth/debug/tone-player-bluetooth-debug.apk` |
| Fonte auxiliar telefone | `tone-player/build/outputs/apk/phone/debug/tone-player-phone-debug.apk` |

Os APKs debug servem para avaliação local. A compilação e os testes automatizados não substituem a verificação das saídas no POCO F7.

O Gradle Wrapper original está incluído e verifica o SHA-256 da distribuição 8.11.1. A primeira compilação baixa o Gradle e as dependências Android; as próximas podem reutilizar o cache.

## Diagnóstico e compatibilidade

O relatório deve distinguir permissão negada, API indisponível, falha de roteamento e interrupção entre aplicativos. Ele reúne metadados técnicos de dispositivos, aplicativos e sessão; não grava o áudio reproduzido. Antes de compartilhar, confira se nomes de dispositivos ou identificadores do Bluetooth revelam informações que você prefere remover.

- [Compatibilidade e resultados](docs/compatibility.md)
- [Protocolo de teste e interpretação dos erros](docs/test-protocol.md)
- [Arquitetura e limites](docs/architecture.md)

Este repositório contém a prova de conceito experimental. Uma versão completa e a ampliação da compatibilidade dependem dos resultados dos testes físicos.

## Licença

O código original está sob a [licença MIT](LICENSE). As dependências mantêm suas próprias licenças; consulte [NOTICE](NOTICE).

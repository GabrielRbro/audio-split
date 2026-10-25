# Fontes auxiliares de áudio

Este módulo gera dois aplicativos com UIDs diferentes:

| Variante | Pacote | Tom | Destino desejado |
| --- | --- | --- | --- |
| `bluetoothDebug` | `io.github.audiosplit.tone.bluetooth` | 440 Hz | Caixa Bluetooth |
| `phoneDebug` | `io.github.audiosplit.tone.phone` | 880 Hz | Alto-falante do celular |

Os nomes indicam a configuração que o aplicativo principal deve aplicar. **Os auxiliares não selecionam dispositivos de saída**: ambos geram `USAGE_MEDIA`, e o Android e as políticas do Audio Split decidem a rota efetiva. Ouvir os dois tons na mesma saída representa uma falha na separação, mesmo que os nomes dos aplicativos mostrem destinos diferentes.

## Teste

1. Instale os dois APKs e conecte a caixa Bluetooth.
2. No aplicativo principal, selecione as fontes auxiliares, faça o diagnóstico e aplique a separação.
3. Abra **Tom A · Bluetooth**, comece com volume baixo, deixe **Solicitar foco de áudio** desativado e inicie o tom.
4. Abra **Tom B · Celular**, também sem solicitar foco, e inicie o segundo tom. Confira cada saída separadamente.
5. Pare os dois. Repita com **Solicitar foco de áudio** ativado nos dois aplicativos e alterne a ordem de início. Esse modo respeita perda de foco, para verificar se o sistema interrompe as fontes.
6. Pare pelos próprios aplicativos ou pela ação da notificação; restaure as políticas no aplicativo principal ao concluir.

Permitir notificações é opcional para a reprodução, mas disponibiliza a ação de parar o teste fora da tela do aplicativo. O serviço de reprodução mantém o tom ao alternar aplicativos e bloquear a tela. A sessão para automaticamente após 10 minutos e não reinicia após encerramento do processo.

Os tons têm pulsos de 0,75 segundo, seguidos de 0,75 segundo de silêncio, com rampas de 20 ms e amplitude máxima de 8% da escala PCM. O volume audível depende do volume de mídia e dos dispositivos; use um nível baixo ao iniciar.

## Compilação

```sh
./gradlew :tone-player:assembleBluetoothDebug :tone-player:assemblePhoneDebug
```

Requer Android 16 (API 36). Os testes finais de rota, reprodução simultânea e tela bloqueada devem ser feitos no aparelho físico.

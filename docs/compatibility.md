# Compatibilidade

**Estado geral: FALHA DE ROTEAMENTO OBSERVADA.** A versão 0.1.0 foi bloqueada na pré-verificação. Na 0.1.1 o usuário relatou troca da saída do Instagram ao iniciar o Spotify; o objetivo de separação simultânea não foi atendido.

Este documento identifica o alvo inicial e registra as evidências disponíveis. Não há afirmação de suporte ao POCO F7, ao HyperOS em geral ou a outras marcas.

## Observação recebida na versão 0.1.0

O usuário relatou Spotify e Instagram reproduzindo no Bluetooth. A captura mostra o Shizuku conectado, Android 16 e a saída **soundcore Liberty 4 NC** selecionada. A mensagem de estado foi: “Este serviço precisa executar como shell por meio do Shizuku, sem root.”

Essa mensagem corresponde à pré-verificação incorreta do contexto: nenhuma política havia sido aplicada. O resultado não avalia se o firmware aceita as duas saídas, pois o teste foi bloqueado antes dessa etapa. A correção 0.1.1 remove essa confusão entre identidade do processo e atribuição do contexto, preservando a exigência de UID shell e da permissão efetiva.

## Observação recebida na versão 0.1.1

O usuário relatou que os dois sons auxiliares tocam simultaneamente, mas ambos no Bluetooth. No cenário real, pausar o Spotify deixa o Instagram reproduzir no celular; ao retomar o Spotify, os dois passam ao Bluetooth. A reprodução simultânea, por si só, não demonstra separação.

Essa evidência é compatível com a reutilização de uma mesma saída do sistema pelas duas regras `RENDER`. Sem o relatório técnico do aparelho, a associação exata das regras às saídas do firmware continua pendente. A versão 0.2.0 adiciona observação das rotas reportadas dos players e detecção de divergência persistente; não altera esse resultado físico nem comprova uma correção.

## Alvo inicial informado pelo usuário

| Campo | Valor |
| --- | --- |
| Aparelho | POCO F7 |
| Android | 16 |
| Build Android | BP2A.250605.031.A3 |
| HyperOS | 3.0.303.0.WOLMIXM.C06 |
| Atualização de segurança | 2026-07-01 |
| Forma de execução pretendida | Shizuku via depuração sem fio, sem root |
| Saídas pretendidas | Uma caixa Bluetooth e alto-falante integrado |

Esses dados vêm da identificação e da imagem fornecidas pelo usuário; não resultam de uma conexão com o aparelho.

## Registro do teste físico

Preencher após executar o [protocolo](test-protocol.md). Um campo não preenchido significa que o teste não foi realizado.

| Campo | Resultado |
| --- | --- |
| Data do teste | Não testado |
| Versão do APK / revisão do código | 0.1.1, conforme sequência de instalação e relato do usuário |
| Versão e estado do Shizuku | Não testado |
| Modelo da caixa / tipo de saída Bluetooth | Não testado |
| Versão do Spotify | Não testado |
| Versão do Instagram | Não testado |
| Opção HyperOS de múltiplas fontes disponível / ativada | Não testado |
| Permissão efetiva `MODIFY_AUDIO_ROUTING` do shell | Não testado |
| Descoberta das saídas | Não testado |
| Registro das políticas | Não testado |
| Fontes auxiliares separadas nas duas ordens | Falha: ambos os sons no Bluetooth; ordem completa não informada |
| Spotify apenas no Bluetooth | Não testado |
| Instagram apenas no alto-falante | Falha: muda para Bluetooth quando o Spotify inicia |
| Ambos simultâneos por cinco minutos, sem pausa ou redução causada pelo outro | Não testado |
| Início na ordem inversa | Não testado |
| Alternância de aplicativos e tela bloqueada | Não testado |
| Desconexão e reconexão Bluetooth | Não testado |
| Encerramento da sessão privilegiada | Não testado |
| Restauração manual | Não testado |
| Diagnóstico exportado / referência local | Não testado |
| Conclusão | Objetivo não atendido: destinos deixam de ser exclusivos durante reprodução simultânea |

## Classificação do resultado

| Classificação | Evidência necessária |
| --- | --- |
| Objetivo comprovado nesta configuração | Apps reais tocam simultaneamente por cinco minutos nas duas ordens, cada um apenas na saída escolhida; restauração e interrupções da sessão passam |
| Resultado parcial: roteamento | As saídas se separam, mas a reprodução simultânea falha ou não foi concluída |
| Bloqueio de permissão | Diagnóstico identifica a permissão necessária ausente sob identidade shell |
| Bloqueio de API ou roteamento | API indisponível, rejeição da política ou destino efetivo incompatível com a regra |
| Inconclusivo | Evidência insuficiente, observações contraditórias ou ausência de confirmação das saídas |

O resultado se aplica à combinação registrada de firmware, caixa e versões dos aplicativos. Atualizações podem alterar o comportamento. Uma falha nessa configuração não demonstra inviabilidade universal sem root.

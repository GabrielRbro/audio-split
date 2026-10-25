# Compatibilidade

**Estado geral: NÃO TESTADO NO APARELHO.**

Este documento identifica o alvo inicial e reserva espaço para evidências do teste físico. Não há afirmação de suporte ao POCO F7, ao HyperOS em geral ou a outras marcas.

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
| Versão do APK / revisão do código | Não testado |
| Versão e estado do Shizuku | Não testado |
| Modelo da caixa / tipo de saída Bluetooth | Não testado |
| Versão do Spotify | Não testado |
| Versão do Instagram | Não testado |
| Opção HyperOS de múltiplas fontes disponível / ativada | Não testado |
| Permissão efetiva `MODIFY_AUDIO_ROUTING` do shell | Não testado |
| Descoberta das saídas | Não testado |
| Registro das políticas | Não testado |
| Fontes auxiliares separadas nas duas ordens | Não testado |
| Spotify apenas no Bluetooth | Não testado |
| Instagram apenas no alto-falante | Não testado |
| Ambos simultâneos por cinco minutos, sem pausa ou redução causada pelo outro | Não testado |
| Início na ordem inversa | Não testado |
| Alternância de aplicativos e tela bloqueada | Não testado |
| Desconexão e reconexão Bluetooth | Não testado |
| Encerramento da sessão privilegiada | Não testado |
| Restauração manual | Não testado |
| Diagnóstico exportado / referência local | Não testado |
| Conclusão | Não testado |

## Classificação do resultado

| Classificação | Evidência necessária |
| --- | --- |
| Objetivo comprovado nesta configuração | Apps reais tocam simultaneamente por cinco minutos nas duas ordens, cada um apenas na saída escolhida; restauração e interrupções da sessão passam |
| Resultado parcial: roteamento | As saídas se separam, mas a reprodução simultânea falha ou não foi concluída |
| Bloqueio de permissão | Diagnóstico identifica a permissão necessária ausente sob identidade shell |
| Bloqueio de API ou roteamento | API indisponível, rejeição da política ou destino efetivo incompatível com a regra |
| Inconclusivo | Evidência insuficiente, observações contraditórias ou ausência de confirmação das saídas |

O resultado se aplica à combinação registrada de firmware, caixa e versões dos aplicativos. Atualizações podem alterar o comportamento. Uma falha nessa configuração não demonstra inviabilidade universal sem root.

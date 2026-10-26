# Arquitetura e limites da prova de conceito

## Objetivo e fronteira

O protótipo investiga roteamento de **áudio de mídia por aplicativo**. Seu primeiro alvo é uma caixa Bluetooth conectada e o alto-falante integrado do POCO F7 com Android 16. Não é um seletor de saídas para conteúdos individuais dentro do mesmo aplicativo.

O Spotify e o Instagram continuam responsáveis por obter e reproduzir suas mídias. O protótipo não usa suas APIs de streaming, não faz login nesses serviços e não copia o áudio para um segundo player.

## Componentes

| Componente | Responsabilidade |
| --- | --- |
| APK principal (`io.github.audiosplit`) | Interface Compose, escolha do cenário e da saída Bluetooth, autorização Shizuku, apresentação e exportação do diagnóstico |
| `UserService` do Shizuku | Executar chamadas privilegiadas sob identidade shell, diagnosticar permissões, aplicar e remover políticas de roteamento |
| Núcleo de sessão | Gerenciar estado, falhas parciais e limpeza das políticas pertencentes ao protótipo |
| Fonte Bluetooth (`io.github.audiosplit.tone.bluetooth`) | Reproduzir um sinal identificável sob UID próprio, sem escolher sua saída |
| Fonte telefone (`io.github.audiosplit.tone.phone`) | Reproduzir um segundo sinal sob outro UID, sem escolher sua saída |

O contrato interno oferece diagnóstico, aplicação das rotas do cenário, consulta de estado, restauração e associação à sessão cliente. Os resultados em JSON informam versão de esquema, etapa, código, mensagem, estado ativo e detalhes técnicos.

## Roteamento

O motor verifica as permissões efetivas do processo shell e resolve os UIDs dos pacotes no aparelho. A hipótese técnica é registrar `AudioPolicy` com `AudioMix` de saída (`RENDER`) e regras que combinem UID e uso de mídia. O dispositivo escolhido deve ser uma saída Bluetooth realmente conectada; a outra saída é o alto-falante integrado.

As APIs internas do Android 16 são acessadas por um adaptador isolado. Alterações de assinatura, política do fabricante ou suporte das saídas podem impedir a operação mesmo quando a permissão existe. A investigação deve registrar a etapa exata e desfazer as alterações parciais.

As chamadas Binder recebidas do APK precisam ser executadas com identidade shell efetiva. O serviço só deve aceitar seu cliente autorizado, validar as opções recebidas contra os dispositivos descobertos e remover apenas as políticas que criou.

Registrar uma política ou receber um retorno de sucesso não comprova o caminho físico do áudio. O estado aplicado é provisório até a verificação do usuário e dos diagnósticos do sistema.

O gerenciador nativo do Android pode reutilizar uma saída já aberta que suporte os dispositivos dos dois mixes `RENDER`. Nesse caso, as regras de UID não criam caminhos de hardware independentes: uma mudança no dispositivo da saída compartilhada pode afetar os dois aplicativos. Isso é compatível com o relato da 0.1.1, mas ainda requer observação no firmware do POCO. Acrescentar `setUidDeviceAffinity` aos mixes atuais também não resolve: o AOSP rejeita afinidades para um UID que já aparece como critério positivo de um mix.

A 0.2.0 observa apenas os players dos aplicativos escolhidos e registra seus dispositivos quando o sistema permite essa leitura. Dispositivos não informados permanecem desconhecidos; a ausência de dados não é tratada como sucesso. Uma divergência persistente durante reprodução simultânea encerra a política experimental e preserva o diagnóstico.

## Atualizações

O atualizador consulta releases estáveis do repositório público `GabrielRbro/audio-split`. O manifesto `update.json` aponta pelo nome para um APK do mesmo release e informa sua versão e SHA256. Após baixar em cache privado, o app confere integridade, identidade do pacote, versão e certificado de assinatura; só então oferece a instalação pelo Android. A sessão de roteamento é restaurada antes de abrir o instalador. A assinatura fixa é um secret do GitHub Actions e não faz parte do repositório.

## Reprodução simultânea

O foco de áudio é um mecanismo separado. O Android ou os próprios aplicativos podem interromper a reprodução quando outro aplicativo solicita foco. A primeira investigação usa a função nativa de múltiplas fontes de áudio do HyperOS, quando disponível e ativada manualmente.

Esta prova de conceito não substitui globalmente a política de foco do Android. Se as saídas forem separadas, mas um aplicativo pausar ou reduzir o volume do outro, o resultado é parcial e deve ser classificado como bloqueio de reprodução simultânea.

## Sessão e restauração

Trocar de aplicativo ou bloquear a tela faz parte do uso esperado durante o teste. O botão **Restaurar áudio padrão** encerra a separação explicitamente. Falha parcial, desconexão Bluetooth, morte do cliente ou encerramento do motor devem limpar os recursos da sessão. Após reconectar ou reiniciar, aplicar as rotas novamente é uma ação explícita.

O motor acompanha tanto o Binder do cliente quanto o Binder do servidor Shizuku. A interface também monitora perda de autorização e solicita a limpeza diretamente ao motor quando ainda é possível alcançá-lo. O ajuste de múltiplas fontes ativado manualmente no HyperOS permanece como configurado pelo usuário; a restauração remove as políticas criadas pelo protótipo.

O aplicativo não deve declarar suporte ao aparelho enquanto os testes reais de saída exclusiva, reprodução simultânea e restauração não forem concluídos.

## Fontes técnicas

- [Shizuku API e `UserService`](https://github.com/RikkaApps/Shizuku-API)
- [Código AOSP de `AudioPolicy`](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/audiopolicy/AudioPolicy.java) — referência da plataforma, não comprovação do comportamento do firmware Xiaomi.
- [Configuração das políticas de áudio Android](https://source.android.com/docs/core/audio/implement-policy)
- [Gerenciamento de mixes no AOSP](https://android.googlesource.com/platform/frameworks/av/+/43d2226f56da1e9b8f6d676d24ccaebda359020c/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp)
- [Foco de áudio Android](https://developer.android.com/media/optimize/audio-focus)
- [Assistente de som Xiaomi](https://www.mi.com/uk/support/article/KA-08490/)

# Protocolo de teste no POCO F7

O teste precisa confirmar três comportamentos: **separação das saídas, reprodução simultânea e restauração**. Retornos de APIs e mensagens de configuração aplicada são evidência técnica auxiliar.

## 1. Registrar o estado inicial

- [ ] Anotar firmware, versões dos APKs, Shizuku, Spotify e Instagram em [compatibility.md](compatibility.md).
- [ ] Conectar a caixa Bluetooth e reproduzir uma mídia normalmente nela.
- [ ] Abrir o protótipo, conceder acesso ao Shizuku e tocar em **Diagnosticar**.
- [ ] Conferir se o diagnóstico reconhece o Shizuku, os aplicativos e as duas saídas.
- [ ] Verificar se existe a função HyperOS de múltiplas fontes de áudio e anotar seu estado. Se disponível, ativá-la manualmente para o cenário simultâneo.

Se o diagnóstico falhar antes de aplicar as rotas, exportar o relatório com a etapa e o erro. Não presumir que um comando de concessão de permissões a um APK comum resolve permissões reservadas ao sistema.

## 2. Fontes auxiliares

Instalar os dois APKs de teste: **fonte Bluetooth** (`io.github.audiosplit.tone.bluetooth`) e **fonte telefone** (`io.github.audiosplit.tone.phone`). Os nomes indicam os destinos desejados, não o destino efetivo da reprodução.

- [ ] No aplicativo principal, escolher o cenário das fontes auxiliares e a caixa Bluetooth conectada.
- [ ] Aplicar a separação.
- [ ] Iniciar cada fonte isoladamente e confirmar de onde sai o som. A fonte Bluetooth deve sair só na caixa; a fonte telefone, só no celular.
- [ ] Reproduzir as duas simultaneamente. Começar com o modo sem solicitação de foco das fontes, para investigar o roteamento separadamente.
- [ ] Repetir iniciando os aplicativos na ordem inversa.
- [ ] Repetir com solicitação de foco ativada nas fontes e registrar qualquer pausa ou redução.
- [ ] Restaurar o áudio padrão e conferir que as regras anteriores deixaram de atuar.

Se as fontes não se separam, interromper a avaliação de sucesso e registrar o destino observado de cada sinal. Um diagnóstico com política registrada não supera esse resultado.

## 3. Spotify e Instagram

- [ ] No aplicativo principal, escolher o cenário dos aplicativos reais, selecionar a caixa e aplicar a separação.
- [ ] Iniciar uma música no Spotify. Confirmar som apenas na caixa.
- [ ] Abrir um vídeo com áudio no Instagram. Confirmar som apenas no celular.
- [ ] Manter a música e reproduzir vídeos durante cinco minutos. Registrar pausas, silenciamento, alteração do volume da música, duplicação ou troca de saída.
- [ ] Repetir começando com o Instagram e depois iniciando o Spotify.
- [ ] Alternar entre os aplicativos e o protótipo. Bloquear e desbloquear a tela durante a reprodução para verificar a continuidade quando os próprios aplicativos permitem áudio em segundo plano.

O Instagram pode interromper seus vídeos ao ficar em segundo plano ou com a tela bloqueada por comportamento próprio. Registre isso separadamente de uma falha do motor de roteamento. O teste de tela bloqueada com fontes auxiliares verifica a vida da sessão sem depender desse comportamento do Instagram.

Pausa ou redução causada pela disputa entre os aplicativos significa resultado parcial, mesmo que as saídas permaneçam separadas. Chamadas e notificações não fazem parte do critério de convivência entre Spotify e Instagram; anote se ocorrerem para não atribuir sua interrupção ao outro aplicativo.

## 4. Desconexão, falhas e restauração

- [ ] Durante a sessão, desconectar a caixa. A configuração do protótipo deve ser removida e o Android deve retomar seu roteamento padrão.
- [ ] Reconectar a caixa. Conferir que a separação não é reaplicada silenciosamente; iniciar uma nova aplicação explícita.
- [ ] Encerrar o Shizuku e conferir o retorno ao comportamento padrão. Reabrir o protótipo e registrar o estado exibido.
- [ ] Encerrar o processo do aplicativo principal durante uma sessão e verificar se suas políticas são limpas.
- [ ] Aplicar novamente e usar **Restaurar áudio padrão**. Confirmar que uma reprodução iniciada depois disso segue a saída padrão do Android.
- [ ] Repetir a restauração quando não houver sessão ativa. A ação deve ser segura e informar o estado atual.

Se o áudio ficar em um destino inesperado, use a restauração, encerre o serviço de teste e reconecte o dispositivo Bluetooth. Caso o sistema ainda não volte ao comportamento normal, reinicie o telefone e anote o ocorrido; não classifique a restauração como aprovada.

## 5. Interpretar e exportar o diagnóstico

Use **Exportar diagnóstico** e acrescente suas observações ao registro de compatibilidade. O relatório técnico não grava a mídia reproduzida. Confira nomes e identificadores dos dispositivos antes de compartilhar.

Na versão 0.2.0, após reproduzir a troca para Bluetooth ao iniciar o Spotify, volte ao Audio Split e exporte o relatório antes de aplicar uma nova separação. O motor mantém até 12 amostras recentes dos players escolhidos, mesmo após restaurar a política por uma divergência persistente (`ROUTE_MISMATCH`). Se o firmware não informar os dispositivos efetivos, o relatório registra essa limitação e a conferência auditiva continua necessária. Uma nova aplicação começa outro histórico.

| Sintoma / etapa | Interpretação a verificar |
| --- | --- |
| Shizuku indisponível ou acesso recusado | Estado do Shizuku e autorização do aplicativo |
| Permissão de roteamento ausente | Privilégio efetivo do processo shell neste firmware |
| Nenhuma caixa elegível | Conexão Bluetooth de mídia e descoberta de saída |
| Aplicativo não encontrado | Instalação do pacote oficial no perfil principal ou dos dois APKs auxiliares |
| API ou registro da política rejeitado | Compatibilidade da API interna, política do fabricante ou suporte da saída |
| Política aplicada, mas saída errada ou duplicada | Roteamento não comprovado; comparar observação com diagnóstico do sistema |
| Saídas corretas, mas um app interrompe o outro | Bloqueio de reprodução simultânea / foco de áudio |
| Restauração falha | Falha de ciclo de vida que impede aprovar a configuração |

Classifique o teste como comprovado somente quando todos os critérios do cenário real e da restauração passarem. Caso contrário, registre a falha específica ou o resultado inconclusivo, preservando a distinção entre o que foi observado e o que ainda é hipótese.

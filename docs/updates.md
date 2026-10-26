# Atualizações pelo GitHub

O aplicativo consulta a última Release pública de `GabrielRbro/audio-split`. Ela precisa conter um APK e `update.json` com `schemaVersion`, `versionCode`, `versionName`, `assetName` e o SHA-256 do APK. Só uma versão com `versionCode` maior é oferecida. O Android sempre pede confirmação para instalar; não há instalação silenciosa.

Antes da instalação, o aplicativo confere o hash, o nome do pacote, a versão e a mesma assinatura da instalação atual. A permissão “Instalar apps desconhecidos” é necessária apenas para abrir o instalador pelo Audio Split. A rota de áudio deve ser restaurada antes de atualizar.

## Configuração única do mantenedor

A chave privada foi criada fora do repositório, no arquivo privado `audio-split-signing-setup.json`. Guarde uma cópia segura desse arquivo: futuras versões precisam da mesma chave. Não publique esse JSON, seu conteúdo nem a chave em commits, issues, logs ou Releases.

1. Abra [New Actions secret](https://github.com/GabrielRbro/audio-split/settings/secrets/actions/new).
2. Use o nome `ANDROID_SIGNING_CONFIG`.
3. Cole como valor o conteúdo completo do arquivo privado `audio-split-signing-setup.json` e salve.
4. Execute novamente o workflow Android na branch `main`, ou envie o próximo commit.

O JSON usa `schemaVersion: 1`, `keystoreBase64`, `storePassword`, `keyAlias` e `keyPassword`. O workflow grava o keystore em um arquivo temporário de acesso restrito, mascara os valores e o remove ao terminar. O certificado público esperado está em [release-certificate.sha256](release-certificate.sha256); nenhuma chave privada fica no código.

Sem o secret, os testes e APKs debug continuam disponíveis, mas nenhuma Release para o atualizador é publicada. Depois que todos os testes e o teste no emulador Android 16 passam, o job `release` compila o APK assinado e verifica sua identidade, versão e certificado. O APK e `update.json` são enviados primeiro para uma Release em rascunho; só depois de todos os arquivos presentes ela vira pública. Assim o app não encontra uma atualização incompleta.

## Publicar a próxima versão

Aumente `versionCode` e `versionName` em `app/build.gradle.kts`; atualize também os nomes do pacote de diagnóstico em `scripts/package-debug.py` e do artifact no workflow. O job cria a tag `v<versionName>`. Assets de uma Release já publicada nunca são substituídos. Conserve o secret e o certificado público; trocar de chave impede que instalações existentes recebam atualizações.

## Primeira instalação assinada

Os APKs debug 0.1.x usavam uma assinatura temporária do ambiente de compilação. Para migrar à primeira Release assinada 0.2.0, desinstale somente o Audio Split antigo e instale o novo APK. O Shizuku e os outros aplicativos podem continuar instalados. As próximas versões assinadas com a mesma chave podem ser instaladas pelo atualizador interno, sem essa reinstalação.

A separação física do áudio continua experimental e precisa ser validada no aparelho; ter um atualizador não confirma compatibilidade de roteamento.

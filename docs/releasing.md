# Gerar uma distribuição

O nome da release no GitHub e o perfil de compilação são conceitos diferentes. A distribuição do getAudioBridge usa Rust em perfil `release`, APK Android não depurável com chave própria e instalador Windows NSIS. Uma versão só deve ser publicada depois de verificar os artefatos gerados.

## Android

Na primeira compilação, com JDK/`JAVA_HOME` e Android SDK configurados:

```powershell
.\scripts\new-signing-key.ps1
.\scripts\android.ps1 build -Release
```

A chave e suas propriedades ficam em `%USERPROFILE%\.android\getAudioBridge-release`, com permissões restritas à conta local e SYSTEM. **Faça uma cópia de segurança protegida dessa pasta.** Todas as atualizações distribuídas por APK precisam preservar a identidade de assinatura. O script se recusa a substituir uma chave existente. Não envie chaves, senhas ou o arquivo `keystore.properties` para o repositório ou para os anexos da release.

Para usar uma chave já existente, defina `GAB_SIGNING_PROPERTIES` apontando para um arquivo de propriedades privado com `storeFile`, `storePassword`, `keyAlias` e `keyPassword`; a configuração usa PKCS12. A build release falha se a configuração de assinatura não estiver disponível, em vez de recorrer à chave debug.

Artefato: `src-tauri/gen/android/app/build/outputs/apk/arm64/release/app-arm64-release.apk`.

Verifique usando o `apksigner.bat` do Android SDK:

```powershell
apksigner.bat verify --verbose --print-certs app-arm64-release.apk
```

Confira também pacote, versão, `versionCode`, ABI arm64 e ausência de `android:debuggable=true` com as ferramentas do SDK. O `versionCode` é definido em `bundle.android.versionCode` no `tauri.conf.json`; incremente-o em atualizações.

### Migrar da prévia debug

A prévia `v0.1.0-alpha.1` usava a chave debug. O Android não permite substituí-la diretamente por um APK de mesmo pacote assinado com a nova chave de distribuição. Guarde seu IP, porta e buffer, desinstale a prévia e instale o APK release uma vez. Atualizações posteriores usarão a mesma chave e poderão ser instaladas normalmente sobre a versão anterior. A aplicação só persiste essas preferências locais; não grava o áudio recebido.

## Windows

```powershell
npm ci
npm run tauri -- build --bundles nsis
```

O instalador aparece em `target/release/bundle/nsis/`. A instalação é por usuário, sem exigir instalação para toda a máquina. WebView2 é um requisito; o instalador pode buscar o runtime se estiver ausente, enquanto o aplicativo já instalado não depende de internet para transmitir áudio.

Um certificado comercial Windows não é requisito para compilar ou distribuir esta versão. O instalador e o executável Windows não têm assinatura de editor confiável; o sistema pode mostrar editor desconhecido. Não descreva a assinatura própria do APK como se também assinasse o executável Windows.

## Verificação antes de publicar

1. Execute `npm run build`, `cargo fmt --all --check` e `cargo test -p bridge-core`.
2. Compile os dois artefatos e confira assinatura/configuração do APK.
3. Instale e teste os artefatos de distribuição reais: captura/recepção, canais de áudio, controles de buffer, reconexão, reinício do transmissor e parada do Android ao perder visibilidade.
4. Observe buffer, perdas, rebufferizações, underruns e correção de relógio durante um trecho contínuo; registre hardware, duração e carga. Logs nativos extras podem ser habilitados para o ensaio com `adb shell setprop log.tag.getAudioBridge DEBUG`; desative ao terminar. Não há registro de PCM nos logs.
5. Documente as limitações observadas. Não confunda buffer ou fila AudioTrack com latência física até o fone.
6. Gere SHA-256 dos arquivos, envie-os a uma release em rascunho, confira tamanho e digest no GitHub e só então publique.

A chave privada não faz parte desse processo de upload. Os logs de diagnóstico e diretórios de compilação também ficam fora do código versionado.

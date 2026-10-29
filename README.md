# getAudioBridge

Áudio do sistema Windows → rede local → Android → saída escolhida pelo sistema (fone com fio, USB-C, alto-falante ou outra rota). Dois alvos **Tauri 2**, interface TypeScript, transporte/buffer em Rust e reprodução Android em Kotlin/AudioTrack. Sem serviços externos em tempo de execução.

## Download

[Baixar getAudioBridge v0.1.0 para Windows e Android](https://github.com/gwennocas/getAudioBridge/releases/tag/v0.1.0).

A primeira versão estável cobre transmissão local Wi-Fi/UDP, Windows x64 e Android arm64 em primeiro plano. A distribuição contém **instalador Windows e APK otimizado assinado com chave própria**, compilados em perfil release. O APK não é depurável. O instalador Windows não tem certificado comercial de editor e requer WebView2, que pode ser instalado quando estiver ausente.

Se você instalou a prévia debug `v0.1.0-alpha.1`, guarde IP, porta e buffer, desinstale-a e instale o novo APK uma vez: a chave de assinatura mudou. Futuras atualizações da distribuição preservarão a chave própria. Consulte [a validação](docs/validation.md) para os testes realizados, equipamentos e limites. A latência física até o fone não foi medida e o desempenho depende da rede e do aparelho.

## Usar

1. Coloque o PC e o Android na mesma rede local. O PC pode estar ligado ao roteador por Ethernet.
2. No Windows, escolha a saída que está tocando áudio. Mantenha “Todas as interfaces” ou selecione o IPv4 da rede local e clique **Iniciar transmissão**.
3. No Android, conecte o fone, digite o IPv4 local exibido no PC, use a mesma porta (padrão **45150/UDP**) e clique **Conectar ao computador**.
4. Comece com buffer de **60 ms**. Diminua se a rede e o aparelho permitirem; aumente se houver rebufferizações. O ajuste altera o alvo, não promete latência total.

O aplicativo Android mantém a tela acesa durante a conexão e **interrompe e desconecta ao perder visibilidade/foco de áudio**. Ao voltar, conecte novamente. Não há serviço ou reprodução em segundo plano. A rota de saída é controlada pelo Android; não há seleção forçada de alto-falante. A saída realmente usada aparece no monitor depois de começar a reprodução.

Se não conectar: confirme IP/porta, evite rede de convidados/isolamento de clientes e permita entrada UDP na porta escolhida no Firewall do Windows, somente em rede confiável. O programa não modifica automaticamente o firewall. Uma conexão USB não transporta áudio nesta versão.

## Compilar

Requisitos: Node 22.12+, Rust estável, ferramentas C++/WebView2 para Tauri no Windows. Android: JDK 21, SDK 36, NDK e target `aarch64-linux-android`. Android mínimo: API 26 (Android 8). A compilação precisa baixar dependências; os binários funcionam sem internet.

```powershell
npm ci
npm run tauri -- dev
npm run tauri -- build --debug --no-bundle
# Instalador Windows, release:
npm run tauri -- build
```

Android (projeto Gradle versionado em `src-tauri/gen/android`):

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
rustup target add aarch64-linux-android
.\scripts\android.ps1 build
.\scripts\android.ps1 devices
.\scripts\android.ps1 install
```

Por padrão o script gera APK debug arm64 para desenvolvimento. Para distribuição, execute `scripts/new-signing-key.ps1` uma única vez e depois `scripts/android.ps1 build -Release`. Preserve a chave privada fora do repositório; ela é necessária para atualizar os APKs. [Instruções de distribuição e assinatura](docs/releasing.md).

O script usa o NDK mais recente do SDK e contorna a exigência de links simbólicos da CLI Tauri em Windows sem Developer Mode: após a compilação Rust, copia o `.so` e empacota com Gradle, sem repetir a tarefa Rust. `-Sdk` permite passar o SDK explicitamente. A descoberta do SDK do projeto vizinho getYoink é uma conveniência deste ambiente; não é dependência do aplicativo. O script impede duas builds Android simultâneas e mantém logs separados por execução.

Saídas:

- Windows: `target/debug/get-audio-bridge.exe`.
- Android: `src-tauri/gen/android/app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`.
- Instalador Windows release: `target/release/bundle/nsis/getAudioBridge_0.1.0_x64-setup.exe`.
- Android release: `src-tauri/gen/android/app/build/outputs/apk/arm64/release/app-arm64-release.apk`.

## Fluxo nativo

```text
Windows: WASAPI loopback → PCM16 estéreo 48 kHz → UDP
Android: UDP Rust → jitter buffer Rust → JNI/direct ByteBuffer → AudioTrack Kotlin
Interface TS ↔ comandos e estatísticas Tauri (sem PCM)
```

WASAPI shared mode faz a conversão do formato da saída para 48 kHz/16-bit/estéreo. COM, captura e envio ficam em uma thread dedicada. Os buffers WASAPI marcados SILENT são convertidos explicitamente em zeros, sem ler um ponteiro nulo. Quando o endpoint inteiro fica ocioso, o transmissor mantém uma linha de silêncio cadenciada. Trocar ou desconectar o dispositivo pode exigir parar e selecionar outra saída.

O Android solicita `PERFORMANCE_MODE_LOW_LATENCY`, usa `THREAD_PRIORITY_AUDIO` e writes nativos bloqueantes em blocos de 240 frames. O sistema pode não conceder o modo de baixa latência. Não são usados Web Audio, elementos HTML de áudio nem eventos IPC para amostras. Chamadas JNI síncronas passam somente um ByteBuffer direto entre Kotlin e Rust.

Se o AudioTrack reportar novos underruns, sua fila efetiva aumenta em passos de 240 frames (5 ms), até 40 ms ou o mínimo maior imposto pelo dispositivo. Esse ajuste é limitado e reinicia na próxima conexão. Ele é independente do alvo do buffer de rede; ambos os tamanhos efetivos ficam visíveis no monitor.

## Transporte, buffer e relógios

- PCM16LE intercalado, 48.000 Hz, estéreo: 1,536 Mbit/s de payload. 200 datagramas/s de 1.000 bytes: 1,6 Mbit/s antes de UDP/IP/Wi-Fi.
- Cliente envia HELLO a cada 500 ms. Servidor responde ao IP/porta de origem e atende um celular por vez. O vínculo expira em 3 s sem HELLO; BYE encerra imediatamente.
- Cada conexão usa token aleatório de 64 bits, cada início de captura usa epoch aleatório. Epoch permite reiniciar o PC sem confundir a nova linha de áudio com pacotes atrasados da anterior. Não há autenticação forte ou criptografia: use rede confiável e não exponha a porta à internet.
- Sequência de 32 bits e timestamp de 64 bits em **frames de áudio**, não horário de parede. Pacotes fora de ordem entram em mapa ordenado; duplicados e pacotes já tocados são descartados.
- Buffer alvo: **20–120 ms**, padrão 60. Armazenamento máximo: 200 ms. Se o atraso exceder alvo + 40 ms, avança para áudio recente. A fila WASAPI também é limitada. O AudioTrack aloca o mínimo recomendado pelo dispositivo e solicita fila efetiva de dois bursts (pelo menos 480 frames/10 ms), sujeita ao limite imposto pelo Android. O tamanho realmente concedido aparece na interface.
- Falta de pacote vira silêncio; não há retransmissão que acumule atraso. Falta de dados suficientes provoca silêncio e novo preenchimento. Um rebuffer não é o mesmo que um underrun do AudioTrack.
- Controle filtrado da ocupação ajusta a razão de consumo por interpolação linear, limitada a ±2.000 ppm. Resolve pequenas diferenças entre os relógios sem alterar `playbackRate` do dispositivo. Picos maiores são tratados por cortes/rebufferização. Essa correção precisa de validação prolongada em cada combinação real de hardware/rede.

Protocolo completo: [docs/protocol.md](docs/protocol.md). Procedimento de medição: [docs/validation.md](docs/validation.md).

## Verificar

```powershell
npm run build
cargo test --workspace --all-targets
cargo check -p get-audio-bridge
```

Os 11 testes exercitam validação de pacotes, perda, duplicação, estéreo, troca de buffer, recuperação de backlog, diferença de relógio nos dois sentidos e erros UDP temporários no Windows. Não substituem medição física de latência, avaliação auditiva ou testes de rotas com fones diferentes.

## Estrutura

- `src/`: controles/indicadores TypeScript e CSS responsivo.
- `crates/bridge-core/`: protocolo UDP, receptor e buffer adaptativo portável.
- `src-tauri/src/windows.rs`: enumeração e captura loopback WASAPI + envio UDP.
- `src-tauri/src/android.rs`: JNI para leitura direta de PCM e encerramento por lifecycle.
- `plugins/tauri-plugin-audio-output/`: integração Kotlin, AudioTrack, foco e lifecycle.
- `scripts/android.ps1`: compilação/instalação Android neste ambiente Windows.

Referências de API: [Tauri mobile plugins](https://v2.tauri.app/develop/plugins/develop-mobile/), [WASAPI loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording), [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack).

# Validação e medição

## v0.2.0 — reprodução em segundo plano e seleção de saída

O APK release arm64 foi compilado com Rust e Kotlin, empacotado com R8 e aprovado nas tarefas `lintVitalArm64Release`. Em 31/10/2026, a verificação do pacote confirmou versão `0.2.0`, `versionCode=2000`, minSdk 26, targetSdk 36, sem `DEBUGGABLE`, e assinatura válida com o mesmo certificado próprio da v0.1.0. Os 11 testes Rust e a compilação TypeScript passaram durante a implementação.

O serviço declara `mediaPlayback`, não é exportado e não encerra ao remover a tarefa. Ele mantém receptor UDP/Rust e AudioTrack fora da Activity/WebView, com MediaSession, notificação de desconexão e locks de CPU/Wi-Fi liberados na parada.

O APK foi instalado com `adb install -r` sobre a distribuição anterior, sem desinstalar ou limpar dados. O Android confirmou `versionName=0.2.0` e `versionCode=2000`. O transmissor Windows v0.1.0 foi iniciado e ficou aguardando conexão.

Testes no mesmo Moto G7 Plus/Android 15, conectado por Wi-Fi ao PC Windows 10/Ethernet, com depuração USB e alimentação conectadas:

- IP `192.168.1.2`, porta `45150` e buffer `60 ms` preservados na interface após a atualização.
- Os botões **Alto-falante**, **Fone / USB** e **Sistema** foram acionados pela interface. A rota nativa mudou para `moto g(7) plus (tipo 2)` e `h2w (tipo 4)`, mesmo com o fone conectado. A seleção é salva no Android.
- Após trocar de saída, o Android inicialmente recriava uma fila nativa de aproximadamente 80 ms. O listener de roteamento reaplica o tamanho desejado; a nova verificação confirmou **480 frames/10 ms** em ambas as rotas.
- Reprodução mantida ao ir para HOME e apagar a tela. O sistema reportou `mWakefulness=Dozing`, serviço de mídia em primeiro plano e sessão `PLAYING`; os contadores de escrita e recepção continuaram, incluindo PCM não silencioso gerado no PC.
- Remoção do cartão dos recentes manteve o áudio. A primeira implementação reabria uma interface vazia; a correção recria a janela Tauri na nova Activity. A interface reaberta mostrou a mesma sessão recebendo áudio.
- Controles de mídia visíveis na tela bloqueada; a ação de pausa/desconexão da sessão foi testada pelo comando de mídia do Android. Serviço, sessão e locks de CPU/Wi-Fi foram liberados após a parada.

No APK final, a observação de **420 segundos** incluiu troca de saídas, HOME, tela apagada, silêncio e áudio ativo do PC: 84.018 pacotes no intervalo, **1 pacote ausente no consumo, 47 rebufferizações Rust, 12 cortes de backlog e 0 underruns AudioTrack**. A maior parte das rebufferizações foi observada enquanto a origem estava silenciosa; isso não demonstra a causa dessas pausas. [Relatório completo](background-hardware-observation.json).

Um recorte com sinal contínuo do PC, fone com fio e tela apagada durou **128 segundos**: 25.599 pacotes, **1 pacote ausente, 3 rebufferizações, 2 cortes de backlog e 0 underruns AudioTrack**. A fila nativa permaneceu em **480 frames/10 ms**. [Relatório com áudio ativo](background-active-observation.json). O alvo Rust era 60 ms; estes resultados não prometem áudio sem cortes. Aumente para 100–120 ms se a rede/aparelho precisar de mais margem. Filas internas não são a latência física até o fone.

Limites: não houve medição física de latência, ensaio de 30–60 minutos, chamadas telefônicas/interrupções de foco reais, adaptador USB-C, outro fabricante ou teste sem alimentação USB. A seleção de saída usa `AudioTrack.setPreferredDevice`; a rota em uso é consultada separadamente, pois [o Android pode sobrepor a preferência](https://developer.android.com/reference/android/media/AudioRouting). Reinicialização e encerramento forçado pelo sistema não garantem continuidade.

## Distinguir os indicadores

- **Buffer atual**: áudio futuro na fila Rust em frames / 48.000.
- **Fila AudioTrack**: frames escritos menos playback head, em ms; não inclui necessariamente todo o hardware.
- **Timestamp de áudio**: quando disponível, estimativa da fila usando posição reportada por AudioTrack. Pode estar indisponível ou representar um instante anterior.
- **Variação de chegada**: média filtrada da diferença entre espaçamento de datagramas e espaçamento na linha de áudio. Não é tempo de trânsito.
- **Correção de relógio**: ajuste de razão de consumo, em ppm. Não mede o desvio isolado dos osciladores.
- **Pacotes ausentes**: lacunas efetivamente encontradas durante o consumo, preenchidas com silêncio.
- **Falhas AudioTrack**: underruns reportados pelo Android. Podem incluir a partida. Rebufferizações e cortes são contadores independentes.

Nenhum desses indicadores é anunciado como latência até o fone.

## Roteiro em aparelho real

1. PC na LAN, Android no Wi-Fi desse roteador; desative isolamento entre clientes se necessário.
2. Instale o APK debug via ADB e abra o app. Não use `adb reverse` para a porta UDP.
3. Inicie o Windows com saída explícita; confirme que o celular exibe recebimento, nome da rota e número crescente de pacotes/escritas.
4. Toque música/fala ou um sinal de teste no PC. Valide auditivamente que o canal esquerdo e direito estão corretos e que silêncio no PC vira silêncio no receptor.
5. Teste buffers de 20, 40, 60, 90 e 120 ms, anotando rebufferizações, underruns, cortes e ocupação durante pelo menos 10 minutos por configuração. Faça um teste de 30–60 minutos para investigar drift.
6. Desconecte/reconecte o cliente, pare/reinicie o transmissor sem fechar o receptor, teste IP errado/porta ocupada, remova a rede e restaure. Confirme retorno à espera e que filas não crescem sem limite.
7. Envie o Android à tela inicial: não deve haver reprodução nem thread UDP ativa. Volte: deve exigir nova conexão.
8. Teste fone P2 e adaptador USB-C separadamente. Verifique `dumpsys audio`/`dumpsys media.audio_flinger` e a rota na UI. Sem o acessório conectado, essa rota não está validada.

Log de reprodução: `adb logcat -s getAudioBridge`. A cada 100 writes registra fila, underruns e rota; não registra amostras nem persiste captura de áudio.

## Medir latência física

Use uma interface/gravador com dois canais simultâneos. Grave um impulso/estalo na saída local do PC e a saída analógica do celular (com atenuação/isolamento adequados) na mesma gravação. Meça a separação dos impulsos por correlação, repita várias vezes e reporte mediana, p95, máximo, configuração de buffer, rotas, aparelho e condição de rede. Para latência absoluta incluindo captura, o ponto de referência precisa ser especificado: duas saídas físicas fornecem diferença entre caminhos, não automaticamente o atraso desde a geração da amostra.

Gravação acústica com microfone também é possível, mas inclui distância dos transdutores, propagação e processamento do microfone. Ping/RTT ou timestamps de datagramas não substituem esse ensaio. Esta implementação não tem sincronização de relógios de parede nem declara um valor de latência ponta a ponta.

## Registro da prévia v0.1.0-alpha.1

Validado em **29/10/2026**, Windows no endereço LAN `192.168.1.2` (Ethernet) e Moto G7 Plus com **Android 15**, Wi-Fi 2,4 GHz e fone com fio. O Android reportou rota **`h2w (tipo 4)`**, e o usuário confirmou que estava ouvindo o áudio. O USB foi usado somente por ADB/diagnóstico, sem encaminhar o áudio UDP.

- Interface TypeScript/Vite compilada; executável Windows debug e APK Android debug arm64 compilados. APK instalado e aberto no aparelho.
- **6 testes Rust aprovados**: validação de wire format, reordenação/duplicação/perda, backlog/underflow, retomada após pausa, drift simulado e integração UDP incluindo epoch novo e BYE.
- Teste de hardware automatizado aprovado: recepção contínua, slider de 20/120/60 ms, parar/reiniciar o transmissor mantendo o cliente, desconectar e reconectar. Relatório de amostras: [hardware-smoke.json](hardware-smoke.json). Reproduzível por `node scripts/smoke.mjs <IP>` com as duas WebViews debug acessíveis conforme o cabeçalho do script.
- Lifecycle real: enviar o app para HOME resultou em `running=false`, buffer nativo liberado (`bufferFrames=0`) e receptor `disconnected`. Ao voltar foi possível conectar novamente.
- Fila AudioTrack inicialmente observada: 3.844 frames, aproximadamente 80 ms. Após solicitar fila efetiva de 480 frames, o aparelho concedeu **480 frames**, com cerca de **10 ms** na métrica por playback head; modo de baixa latência reportado ativo. A métrica por AudioTimestamp indicou aproximadamente 38 ms em uma amostra, evidenciando que as estimativas representam pontos diferentes do caminho. Nenhum desses valores é uma medição de latência física.
- A versão final aumenta a fila nativa em passos de 240 frames quando detecta novos underruns, limitada a 40 ms (ou ao mínimo maior imposto pelo dispositivo). O teste final confirmou a adaptação de 480 para **720 frames / 15 ms** após um underrun; em outra etapa chegou a 960 frames / 20 ms. O relatório final inclui `bufferIncreases`. O ensaio de lifecycle e o protocolo Rust permaneceram iguais.
- O alvo Rust de 60 ms apresentou ocupação variável em torno de 40–60 ms nas amostras. Houve perdas, cortes, rebufferizações e alguns underruns no ensaio sob compilação/carga e durante mudanças de configuração; não se conclui reprodução sem falhas em toda condição. O relatório preserva os contadores acumulados e o reinício deles em uma nova conexão.
- Foi necessária regra de firewall limitada ao executável debug, UDP 45150, PC `192.168.1.2` e celular `192.168.1.4`. Um bloqueio UDP público criado automaticamente para o mesmo executável foi desativado por conflitar com essa permissão. O firewall permaneceu ativo; outras regras não foram alteradas. A regra vale para o caminho do executável testado, não automaticamente para cópias em outra pasta.

Não realizados: medição física por gravador/interface, teste de 30–60 minutos de drift em hardware, qualificação de diferentes aparelhos, adaptador USB-C e distribuição release assinada. O material entregue é uma primeira versão funcional e testada com builds de desenvolvimento.

## Distribuição v0.1.0

Validada em **29/10/2026**, em Windows 10 Pro 22H2/build 19045 e Moto G7 Plus/Android 15 com fone com fio. O usuário confirmou áudio normal no aparelho após instalar o APK otimizado e assinado com chave própria.

Artefatos de distribuição:

- Instalador Windows x64 NSIS por usuário: aproximadamente 1,9 MB, perfil Rust release. A instalação silenciosa retornou sucesso. O hash do executável instalado foi comparado ao executável extraído do próprio instalador e coincidiu.
- APK Android arm64: aproximadamente 9,85 MB, release otimizado por Rust/R8, pacote `app.getaudiobridge.mobile`, versão `0.1.0`, `versionCode=1001`, minSdk 26, targetSdk 36 e sem flag `DEBUGGABLE`.
- APK verificado pelo Android SDK: assinatura v2 válida, RSA 3072, certificado `CN=getAudioBridge`, SHA-256 do certificado `651518cb1710c9a6821a832ff2c1b4b118a7cf62b10265c71de8cc460f6f313e`. A chave privada permanece fora do repositório.
- Migração da prévia debug por reinstalação inicial, reaplicando IP/porta/buffer. Uma atualização posterior entre dois APKs release com a chave própria foi instalada sobre a anterior com sucesso, preservando essas configurações.

**11 testes automatizados aprovados** (`cargo test --workspace --all-targets`): nove testes do protocolo/buffer, um teste de integração UDP e um teste Windows de erros UDP temporários. A cobertura inclui relógio de origem mais rápido e mais lento, estéreo, troca de alvo de buffer e rejeição de timestamps extremos. Compilação TypeScript e formatação Rust verificadas.

Teste do Windows instalado: datagramas de controle inválidos e maiores que o buffer não encerraram a captura; porta ocupada produziu erro recuperável; depois de liberar a porta, transmissão e conexão Android voltaram. [Relatório Windows](windows-stable-smoke.json).

No APK release foram verificados pela interface e logs nativos: conexão, alvos de 20/120/60 ms, reconexão, recuperação após reinício do PC e parada ao enviar o Android para HOME. Após sair, o Windows voltou ao estado de espera sem cliente; ao retornar, o Android exigiu nova conexão. A instalação da distribuição não habilita debugging de WebView; métricas extras de log foram ativadas somente durante a qualificação.

Trecho contínuo com os artefatos finais, alvo de 60 ms e rota `h2w (tipo 4)`: **212 segundos**, 107 amostras nativas e 42.397 pacotes recebidos no intervalo. Foram registrados **0 pacotes ausentes no consumo, 0 cortes de backlog, 0 underruns AudioTrack, 1 rebufferização do jitter buffer e nenhum erro de estado**. Fila nativa: 480 frames/10 ms. Ocupação Rust observada: aproximadamente 38,5–60 ms. [Métricas do trecho](stable-hardware-observation.json).

A rebufferização está registrada e não equivale a um underrun do AudioTrack. O teste não promete ausência de falhas em outras condições; buffers maiores podem ser necessários em redes com pausas maiores. Esses valores são filas e indicadores internos, não latência física até o fone. Permanecem fora desta qualificação: medição física por gravador/interface, ensaio de 30–60 minutos, outros aparelhos e adaptador USB-C.

A versão estável cobre o escopo inicial de transmissão local com o Android aberto. O executável/instalador Windows continua sem certificado comercial de editor; isso é independente da assinatura própria validada no APK. Foram mantidas regras de firewall limitadas ao executável instalado, porta UDP e ao PC/celular deste ensaio.

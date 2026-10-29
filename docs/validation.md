# Validação e medição

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

## Registro desta execução

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

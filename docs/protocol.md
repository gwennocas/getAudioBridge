# GAB1 — versão 1

Todos os inteiros são little-endian. Um datagrama por pacote; rejeitar datagramas truncados ou maiores que o tamanho esperado. IPv4 unicast, UDP, porta padrão do servidor 45150. O socket cliente usa porta efêmera conectada ao servidor indicado manualmente.

| Offset | Bytes | Campo |
| --- | --- | --- |
| 0 | 4 | ASCII `GAB1` |
| 4 | 1 | Versão: 1 |
| 5 | 1 | Tipo: HELLO=1, AUDIO=2, BYE=3 |
| 6 | 1 | Canais: 2 |
| 7 | 1 | Formato: 1 = PCM16LE |
| 8 | 8 | Token aleatório escolhido pelo cliente |
| 16 | 4 | Sequência: `(frame / 240) mod 2^32` |
| 20 | 4 | Sample rate: 48000 |
| 24 | 8 | Posição do primeiro frame na linha da captura |
| 32 | 2 | Frames: 240 para AUDIO, 0 nos controles |
| 34 | 2 | Reservado: zero |
| 36 | 4 | Epoch do transmissor (AUDIO); zero nos controles |
| 40 | 960 | AUDIO: 240 frames L/R intercalados, i16 LE |

HELLO/BYE têm exatamente 40 bytes; AUDIO tem exatamente 1000 bytes (abaixo do MTU usual). Sequência e posição devem corresponder; posições são múltiplos de 240. O token é ecoado pelo servidor no áudio. A origem do datagrama e o token devem corresponder ao único vínculo ativo. Outros clientes aguardam expirar o vínculo ou o BYE.

A linha de frames avança também enquanto não há cliente. Ao iniciar uma nova captura, epoch muda e a linha reinicia. O receptor esvazia o buffer ao observar epoch novo, retendo até oito epochs aposentados para ignorar pacotes antigos em trânsito. Timestamps não podem ser subtraídos de um relógio Android para inferir latência de ponta a ponta.

Keepalive: HELLO a cada 500 ms, expiração em 3 s. Sem AUDIO por 3 s: estado de espera, buffer esvaziado; HELLO continua permitindo reconexão automática enquanto o app está aberto. Ao sair do app, socket e reprodução são encerrados. Reconectar cria novo token.

Sem ARQ/FEC, compressão ou encriptação nesta versão. Perda resulta em silêncio e contagem no momento do consumo; `lost` não estima pacotes que nunca chegaram antes do início da reprodução. A ausência de fluxo completo gera `rebuffers`, não um número fictício de pacotes perdidos.

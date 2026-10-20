# Correção Definitiva da Notificação de Controle no Modo Samsung DeX

Impede que a barra de controle na notificação do Samsung DeX seja rebaixada para a seção de notificações minimizadas ou colapse em uma linha vazia ao iniciar a gravação (`startForegroundSafely`), garantindo que o cronômetro nativo e os botões de ação permaneçam no topo do painel durante toda a sessão.

### User Review & Critical Decisions

> [!IMPORTANT]
> No Android 8+ (e especialmente na One UI / Samsung DeX), o sistema operacional armazena permanentemente em cache a importância de um `NotificationChannel` pelo seu `CHANNEL_ID`. Como o canal antigo (`dex_recorder_channel`) foi registrado anteriormente com `IMPORTANCE_LOW` e a notificação usava `.setSilent(true)` + `PRIORITY_LOW`, o SystemUI do Samsung DeX rebaixa automaticamente o serviço em primeiro plano (`startForeground`) para a seção de notificações minimizadas (abaixo de "Sistema Android"), ocultando o `RemoteViews` customizado.

- **Decisão Confirmada 1 (Novo Canal de Prioridade Padrão sem Som)**: Criação de um novo canal `dex_recorder_control_channel_v2` com `IMPORTANCE_DEFAULT` (e `PRIORITY_DEFAULT`), mantendo `setSound(null, null)` e `enableVibration(false)` para posicionar a barra no topo do painel sem interromper o áudio do jogo e sem rebaixamento pelo SystemUI.
- **Decisão Confirmada 2 (Remoção de `.setSilent(true)` e Conflitos de Estilo)**: Remoção da flag `.setSilent(true)` e de `PRIORITY_LOW` no `NotificationCompat.Builder`, pois na One UI `.setSilent(true)` força a migração da notificação para o grupo minimizado/colapsado assim que `startForeground()` é invocado.
- **Decisão Confirmada 3 (Auto-Minimizar Janela ao Gravar)**: Manutenção do recolhimento automático (`moveTaskToBack(true)`) após a aprovação do `MediaProjection` para liberar a tela imediatamente para o jogo ou área de trabalho DeX.

---

### 1. Overview & Core Concept

- **O Que Faz**: Garante que a barra de controle da notificação (`notification_dex_controls.xml`) permaneça fixa no topo do painel de notificações do Samsung DeX, totalmente expandida com a pílula de status/cronômetro (`00:00 REC` / `PAUSADO` / `PRONTO`) e os botões redondos de ação antes, durante e após a gravação.
- **Público-Alvo / Contexto**: Usuários gravando gameplay em 2K/4K a 60 FPS ou área de trabalho no modo Samsung DeX que dependem do controle rápido via painel de notificações.
- **Valor Principal**: Elimina o colapso do card para uma linha única vazia ao transicionar de ocioso (`notify`) para gravação em primeiro plano (`startForeground`).

---

### 2. User Experience & Visual Design

- **Fluxo do Usuário no Samsung DeX**:
  1. **Estado Pronto (`00:00 PRONTO`)**: Ao abrir o app ou ativar os controles, a notificação aparece no topo do painel com ponto verde, texto `00:00 PRONTO` e botões **Gravar (●)**, **Pincel**, **Facecam** e **Fechar (X)**.
  2. **Início da Gravação (`REC`)**: Ao aprovar a captura de tela, a janela principal é minimizada automaticamente (`moveTaskToBack(true)`). O serviço inicia em primeiro plano no canal de prioridade padrão; a notificação permanece no topo do painel exibindo o ponto vermelho, o `<Chronometer>` nativo contando em tempo real (`00:01`, `00:02`...) e os botões **Pausar (||)**, **Parar (■)**, **Pincel**, **Facecam** e **Fechar (X)**.
  3. **Pausar / Retomar (`PAUSADO` ⇄ `REC`)**: Ao clicar em Pausar na notificação, o cronômetro congela no tempo exato, o rótulo muda para amarelo (`PAUSADO`) e o botão alterna para **Continuar (▶)** com apenas uma atualização pontual.
  4. **Parar (`■`) ou Fechar (`X`)**: Ao clicar em Parar, o vídeo é salvo e a notificação retorna ao estado `00:00 PRONTO`. Ao clicar em Fechar, a gravação (se ativa) é finalizada com segurança e a notificação e sobreposições são encerradas.
- **Identidade Visual & Layout**:
  - *Estética*: Tema escuro utilitário otimizado para Samsung DeX (`#181A20` com bordas sutis e pílula `#232730`), contrastando indicadores semânticos (`#00E676` Pronto, `#FF5252` Gravando, `#FFC400` Pausado).
  - *Estabilidade de Dimensões no RemoteViews*: Altura fixa e explícita nos contêineres e botões (`32dp`) compatível com o limite de altura de `customContentView` da One UI, evitando cortes ou falhas de inflação.

---

### 3. Key Product Decisions & Trade-Offs

- **Decisão 1: Migração para Novo ID de Canal com `IMPORTANCE_DEFAULT` e `PRIORITY_DEFAULT`**
  - *Abordagem Escolhida*: Substituir o ID de canal antigo por um novo ID (`dex_recorder_control_channel_v2`) configurado com `NotificationManager.IMPORTANCE_DEFAULT` (com som nulo e sem vibração) e remover `.setSilent(true)` do `NotificationCompat.Builder`.
  - *Por Quê*: O Android ignora alterações de importância em canais já criados. Além disso, na One UI (Samsung DeX), qualquer `ForegroundService` associado a um canal `IMPORTANCE_LOW` ou marcado com `.setSilent(true)` é movido para a seção inferior de "Notificações minimizadas" e tem seu `customContentView` suprimido em uma linha simples.
  - *Alternativas Consideradas*: Manter `IMPORTANCE_LOW` — descartado porque a One UI força o colapso de serviços em primeiro plano de baixa prioridade.

- **Decisão 2: Preservação de `RemoteViews` sem `DecoratedCustomViewStyle` Conflitante na One UI**
  - *Abordagem Escolhida*: Fornecer instâncias dedicadas de `RemoteViews` em `setCustomContentView`, `setCustomBigContentView` e `setCustomHeadsUpContentView`, definindo `setContentTitle` / `setContentText` de fallback não-vazio apenas para acessibilidade do sistema e garantindo que todas as operações no `RemoteViews` usem apenas métodos suportados nativamente (`setViewVisibility`, `setImageViewResource`, `setChronometer`, `setTextViewText`, `setOnClickPendingIntent`).
  - *Por Quê*: Se um `RemoteViews` falhar ao inflar ou se o estilo do sistema colapsar o cabeçalho quando não há título padrão em um serviço `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, a One UI descarta o layout customizado.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Diagrama de Arquitetura e Fluxo da Notificação**:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     MainActivity / FloatingBallView                      │
│  • Aprova MediaProjection -> prepareForRecordingStart()                  │
│  • Chama startForegroundService(ACTION_START) + moveTaskToBack(true)     │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         ScreenRecordingService                           │
│  • Canal: "dex_recorder_control_channel_v2" (IMPORTANCE_DEFAULT, mudo)   │
│  • Remove canal antigo ("dex_recorder_channel") do NotificationManager   │
│  • startForegroundSafely() -> buildControlNotification() (1x no início)  │
│  • Sem setSilent(true) e com PRIORITY_DEFAULT + FOREGROUND_IMMEDIATE     │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│              RemoteViews (notification_dex_controls.xml)                 │
│  ┌────────────────────────┐   ┌───────────────────────────────────────┐  │
│  │ ● <Chronometer> STATUS │   │ [● Gravar] [||/▶] [■] [✏] [📷] [✕]   │  │
│  └────────────────────────┘   └───────────────────────────────────────┘  │
│  • Contagem nativa no SystemUI (zero chamadas notify() no loop de 1s)    │
│  • PendingIntents diretos para Pausar/Continuar, Parar, Pincel, Facecam  │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Mapeamento de Estados e Interações**:
  - **Estado `Idle` (`!isRecording`)**: `notif_chronometer` parado com texto `"00:00"`, rótulo `"PRONTO"` (`#00E676`), `btn_notif_record` `VISIBLE`, `btn_notif_pause_resume` e `btn_notif_stop` `GONE`.
  - **Estado `Recording` (`isRecording && !isPaused`)**: `notif_chronometer` ativo (`started = true`) com `base = chronometerBaseRealtime`, rótulo `"REC"` (`#FF5252`), `btn_notif_record` `GONE`, `btn_notif_pause_resume` (`ic_notif_pause`) e `btn_notif_stop` `VISIBLE`.
  - **Estado `Paused` (`isRecording && isPaused`)**: `notif_chronometer` pausado (`started = false`) mantendo a base deslocada da duração gravada, rótulo `"PAUSADO"` (`#FFC400`), `btn_notif_pause_resume` alternado para `ic_notif_play`.
  - **Sincronização de Ferramentas (`isBrushActive`, `isFacecamActive`)**: Atualização pontual via `showOrUpdateControlNotification` apenas quando o usuário ativa/desativa o Pincel ou a Facecam, preservando a base do cronômetro em execução.

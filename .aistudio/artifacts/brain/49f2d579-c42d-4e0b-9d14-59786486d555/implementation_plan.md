# Funcionamento Contínuo da Notificação de Controle em Gravações Seguidas (One UI 7.0 / Samsung DeX)

Garante que a barra de controle customizada (`RemoteViews`) no painel de notificações do Samsung DeX mantenha o `<Chronometer>` e todos os 5 botões de ação visíveis e funcionais não apenas na 1ª gravação, mas em todas as gravações subsequentes (2ª, 3ª, 4ª... seguidas), eliminando o *Cooldown / CustomView Stripping* do SystemUI na One UI 7.0 (Android 15).

## User Review & Critical Decisions

> [!IMPORTANT]
> As decisões abaixo adotam os padrões recomendados para contornar o limite por `NotificationEntry` e a condição de corrida do `stopForeground` no Android 15 / One UI 7.0:

- **Decisão Confirmada 1 — Isolamento de `NotificationEntry` com IDs Dinâmicos por Sessão**: Cada nova gravação iniciada no `Foreground Service` recebe um identificador de notificação exclusivo e incremental (ex.: `2001`, `2002`, `2003`...), separado do identificador do estado ocioso (`00:00 PRONTO`). Dessa forma, o SystemUI da One UI 7.0 trata a 2ª, 3ª e demais gravações exatamente como uma 1ª gravação inédita (*cold start*), sem herdar contadores de atualização ou flags de `stopForeground` da sessão anterior.
- **Decisão Confirmada 2 — Restauração Escalonada na Thread Principal após `stopForeground`**: Ao finalizar uma gravação, a remoção do estado *Foreground* (`stopForeground(STOP_FOREGROUND_REMOVE)`) e o encerramento da sessão ocorrem na *Main Thread*, cancelando o ID da gravação finalizada antes de publicar a nova barra `00:00 PRONTO`, eliminando colisões assíncronas de IPC entre o `ActivityManagerService` e o `NotificationManagerService`.

---

## 1. Overview & Core Concept

- **O Que Faz**: Preserva a renderização completa da barra de controle do **DexRecorder** no topo do painel de notificações do Samsung DeX ao longo de múltiplas sessões consecutivas de gravação de tela na mesma execução do aplicativo.
- **Público-Alvo / Contexto**: Usuários do Galaxy S23 Ultra (e demais dispositivos Samsung com One UI 7.0 / Android 15) no modo desktop Samsung DeX que realizam várias gravações seguidas controlando tudo diretamente pelo painel de notificações.
- **Valor Principal**: Elimina a necessidade de fechar o aplicativo e aguardar minutos para que o SystemUI resete o estado da notificação entre uma gravação e outra.

---

## 2. User Experience & Visual Design

- **Fluxo Contínuo em Múltiplas Gravações Seguidas**:
  1. **Abertura / Estado Pronto (`00:00 PRONTO`)**: Exibe o card escuro no topo do painel do Samsung DeX com indicador verde `PRONTO` e 4 botões redondos (**Gravar**, **Pincel**, **Facecam**, **Fechar**).
  2. **1ª Gravação (`00:00 REC`)**: Ao aprovar a captura, a notificação ociosa é substituída por uma nova entrada de *Foreground Service* exibindo o `<Chronometer>` nativo em contagem contínua e os 5 botões (**Pausar/Continuar**, **Parar**, **Pincel**, **Facecam**, **Fechar**).
  3. **Parada da 1ª Gravação (`■`)**: O vídeo é salvo, a notificação da 1ª sessão de gravação é encerrada de forma limpa na *Main Thread* e o painel restaura uma nova entrada limpa no estado `00:00 PRONTO`.
  4. **2ª, 3ª e N-ésima Gravações Seguidas (`●` -> `00:00 REC`)**: Ao clicar em **Gravar** novamente (pela notificação, balão flutuante ou janela do app), a sessão de gravação inicia com um novo ID de notificação exclusivo, exibindo imediatamente o `<Chronometer>` ativo e os 5 botões de controle sem sofrer *stripping* para cabeçalho vazio.
- **Identidade Visual & Tema**:
  - *Direção Estética*: Utilitário escuro nativo para Samsung DeX (`#141820` com borda `#2A3140`), pílula de status `#1E2430` e botões circulares compactos (`30dp`).
  - *Otimização de Superfícies `RemoteViews`*: Fornece apenas as visões compacta (`CustomContentView`) e expandida (`CustomBigContentView`), removendo a 3ª cópia desnecessária de *Heads-Up* (`CustomHeadsUpContentView`) para reduzir em 33% o peso do pacote *Binder/Parcel* e a quota de inflação do SystemUI.

---

## 3. Key Product Decisions & Trade-Offs

- **Decisão 1: IDs de Notificação Exclusivos por Sessão vs. Reuso de ID Fixo (`1001`)**
  - *Abordagem Escolhida*: Manter um gerador atômico de IDs de sessão para que cada ciclo de gravação (`startForeground`) utilize um ID inédito (`2001`, `2002`, `2003`...), separado do ID da barra ociosa, cancelando o ID anterior na transição.
  - *Por quê*: Na One UI 7.0 (Android 15), o `NotificationManagerService` e o `ForegroundServiceController` associam políticas de *cooldown* e histórico de inflação de `RemoteViews` à chave da `NotificationEntry` (`pkg + id`). Quando o mesmo ID `1001` recebe `stopForeground(STOP_FOREGROUND_REMOVE)` seguido imediatamente de `notify(1001)` e de um novo `startForeground(1001)`, o SystemUI descarta o `customContentView` nas sessões seguintes até que o processo/entrada expire após vários minutos.
  - *Alternativas Consideradas*: Manter `NOTIFICATION_ID = 1001` fixo com apenas `delay` antes do `notify`, o que ainda acumula histórico de atualizações na mesma `NotificationEntry` e falha na 2ª ou 3ª gravação.
- **Decisão 2: Criação Única do Canal de Notificação e Redução de Instâncias `RemoteViews`**
  - *Abordagem Escolhida*: Consultar `getNotificationChannel` antes de criar o canal `dex_recorder_control_channel_v2` (evitando chamadas repetidas a `createNotificationChannel` e `deleteNotificationChannel` em cada atualização) e anexar apenas 2 instâncias de `RemoteViews` (recolhida e expandida) em vez de 3.
  - *Por quê*: Reduz drasticamente o tráfego IPC com o `NotificationManagerService` durante a transição entre parar a 1ª gravação e iniciar a 2ª gravação.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Arquitetura e Ciclo de Vida de IDs no SystemUI (One UI 7.0)

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    Gerenciador de Sessões de Notificação                  │
│   • IDLE_NOTIFICATION_ID (1001)  • sessionNotifIdGen (2001, 2002, 2003…)  │
└───────────────────┬───────────────────────────────────┬───────────────────┘
                    │                                   │
        [Estado Pronto: 00:00 PRONTO]        [Nova Gravação: ACTION_START]
                    │                                   │
                    ▼                                   ▼
┌───────────────────────────────────────┐ ┌─────────────────────────────────┐
│ 1. Cancela ID de gravação anterior    │ │ 1. Cancela IDLE_NOTIFICATION_ID │
│ 2. Publica barra PRONTO no ID 1001    │ │ 2. Gera novo ID (ex.: 2002)     │
│    (4 botões: ●, ✏️, 📷, X)           │ │ 3. Chama startForeground(2002)  │
└───────────────────┬───────────────────┘ │    (5 botões: ||, ■, ✏️, 📷, X) │
                    │                     └─────────────┬───────────────────┘
                    │                                   │
                    │        [Parar Gravação: ■]        │
                    └───────────────────────────────────┘
                      Executa na Main Thread:
                      • stopForeground(STOP_FOREGROUND_REMOVE)
                      • Cancela ID da sessão encerrada (2002)
                      • stopSelf() e restaura barra PRONTO (1001)
```

### Mapeamento de Estado e Transições Interativas

- **Início da 1ª, 2ª, 3ª... Gravação (`prepareForRecordingStart` -> `startForegroundSafely`)**:
  - Ao solicitar o início da gravação (via botão da notificação, balão flutuante ou tela principal), um sinalizador de transição impede que `MainActivity.onResume()` emita atualizações intermediárias na barra ociosa enquanto o diálogo do `MediaProjection` é exibido.
  - Assim que o usuário autoriza o `MediaProjection`, o serviço cancela o `IDLE_NOTIFICATION_ID`, incrementa o identificador de sessão (`activeRecordingNotificationId`) e invoca `startForeground(activeRecordingNotificationId, notification, serviceType)` com `RemoteViews` recém-construído e `<Chronometer>` nativo.
- **Pausar / Continuar Durante Qualquer Sessão (`pauseRecording` / `resumeRecording`)**:
  - Atualiza diretamente o `activeRecordingNotificationId` da sessão em andamento para alternar entre `REC` (`||`) e `PAUSADO` (`▶`).
- **Parar Gravação (`stopRecordingSession`)**:
  - Finaliza o `AudioCaptureAndEncoder`, `ScreenVideoEncoder` e `MediaMuxerManager` em *background* (`Dispatchers.IO`) e persiste o vídeo no `MediaStore` / `Room`.
  - No bloco `finally`, alterna para `Dispatchers.Main`, executa `stopForeground(STOP_FOREGROUND_REMOVE)` e `nm.cancel(finishedRecordingId)`, chama `stopSelf()` e restaura a notificação `00:00 PRONTO` de forma limpa para a próxima gravação.

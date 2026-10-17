# 🎥 DexRecorder — Gravador de Tela Profissional para Android & Samsung DeX

O **DexRecorder** é um aplicativo nativo de gravação de tela e áudio de alta performance para Android, desenvolvido em **Kotlin** com **Jetpack Compose**. Projetado para oferecer recursos de nível profissional — sem marcas d'água, sem limites de tempo e sem necessidade de root —, o grande diferencial do projeto é sua **otimização nativa para o modo desktop (Samsung DeX)** e seu **pipeline avançado de captura de áudio interno**.

## ✨ Principais Funcionalidades

### 🖥️ Otimização Real para Samsung DeX (Segunda Tela)

* **Detecção Automática de Monitor Externo:** Identifica quando o aparelho está operando em modo Desktop (`UI_MODE_TYPE_DESK` / `semDesktopModeEnabled`) e direciona a captura diretamente para o display secundário, evitando o efeito de *windowboxing* (vídeo quadrado espremido no centro da tela).

* **Ajuste Dinâmico de DPI e Resolução:** Adapta automaticamente a densidade de pixels do monitor externo (padrão desktop 160 DPI) e grava em proporção Widescreen nativa (16:9 / 1920x1080 ou superior).

* **Multi-Janela Estável:** Suporte completo a redimensionamento livre de janelas sem reiniciar a `Activity` ou interromper a gravação em andamento.

### 🔊 Sistema Avançado de Captura de Áudio

* **Apenas Áudio Interno (Sem Ruído Externo):** Captura o som puro diretamente da placa de som do sistema via `AudioPlaybackCaptureConfiguration` (Android 10+).

* **Compatibilidade Total com Periféricos:** Grava o áudio interno perfeitamente mesmo quando o usuário está utilizando **fones de ouvido via Dongle USB-C, cabo P2 ou Bluetooth**.

* **Áudio Interno + Microfone (Audio Mixer):** Permite gravar o som do jogo/sistema simultaneamente com a voz do usuário para gameplays comentadas e tutoriais.

* **Qualidade de Estúdio:** Amostragem nativa em **48.000 Hz (48 kHz) Estéreo** com codificação **AAC (128/192 kbps)**, alinhada ao hardware de aparelhos topo de linha (Snapdragon/Exynos) para eliminar engasgos ou cortes de buffer.

### 🎬 Gravação de Vídeo de Alta Performance

* **Resoluções Suportadas:** 240p, 480p, 720p (HD), 1080p (Full HD) e até 4K (Ultra HD).

* **Taxa de Quadros Fluida:** Opções de gravação a **30 FPS** e **60 FPS** com controle de Bitrate (até 12 Mbps+).

* **100% Limpo:** Vídeos exportados sem marca d'água e sem limite de tempo de gravação.

### 🛠️ Ferramentas e Controle em Tempo Real

* **Notificação Permanente Interativa:** Serviço em primeiro plano (`ForegroundService`) silencioso com cronômetro em tempo real e botões de ação rápida para **Pausar**, **Continuar**, **Parar** e **Fechar**.

* **Bola Flutuante (Overlay Widget):** Atalho rápido sobreposto à tela com opacidade ajustável para controle da gravação e captura de tela (*Screenshot*).

* **Ferramenta de Pincel (Brush Tool):** Desenhe e faça marcações na tela em tempo real durante suas gravações ou tutoriais.

* **Facecam Flutuante:** Janela de câmera sobreposta redimensionável (frontal/traseira) utilizando CameraX.

## 🏗️ Arquitetura e Engenharia de Mídia

O núcleo de gravação do **DexRecorder** foi construído utilizando as APIs de baixo nível do Android (`MediaCodec`, `MediaMuxer`, `MediaProjection` e `AudioRecord`) em vez de depender apenas do `MediaRecorder` básico, garantindo controle total sobre os pacotes de áudio e vídeo:

1. **Multithreading Desacoplado (Coroutines):**

   * O processamento de vídeo e a leitura de pacotes PCM de áudio rodam em *threads* independentes (`Dispatchers.IO`), com a thread de áudio configurada em prioridade máxima de hardware (`THREAD_PRIORITY_URGENT_AUDIO`) e fila assíncrona (`ConcurrentLinkedQueue`).

2. **MediaMuxer Thread-Safe:**

   * Todas as operações de escrita (`writeSampleData`) são protegidas por bloqueios explícitos de sincronização (`synchronized(muxerLock)`), garantindo que as tabelas de índice do contêiner `.mp4` nunca sejam corrompidas.

3. **Sincronia Estrita de Tempo (PTS Monotônico):**

   * Cálculo de *Presentation Time Stamp* (PTS) linear baseado na contagem acumulada de bytes de áudio lidos e solicitação imediata de *I-Frame/Keyframe* (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) no início da gravação para reprodução instantânea a partir do segundo `00:00`.

4. **Armazenamento Moderno (`MediaStore`):**

   * Integração direta com a API `MediaStore` salvando os vídeos na pasta pública `Movies/DexRecorder`, garantindo visibilidade imediata na Galeria nativa sem duplicação de arquivos.

## 📱 Compatibilidade de Sistema

| **Funcionalidade** | **Versão Mínima do Android** | **Observações** | 
| **Instalação do Aplicativo (.apk)** | Android 7.0 (API 24) | Compatível com smartphones e tablets | 
| **Gravação de Tela + Microfone** | Android 7.0 (API 24) | Funcionamento completo | 
| **Gravação de Áudio Interno** | **Android 10+ (API 29)** | Requer API `AudioPlaybackCapture` nativa | 
| **Modo Desktop (Samsung DeX)** | Android 10+ (One UI) | Linhas Galaxy S (ex: S20 FE, S23 Ultra, S25), Note e Tab S | 

> **⚠️ Nota sobre políticas de privacidade do Android:**
>
> * **Apps com proteção DRM:** Aplicativos que bloqueiam explicitamente a captura de áudio por diretrizes do sistema (como o app nativo do YouTube, Netflix ou Spotify) terão o áudio silenciado pelo próprio Android. *Dica: Vídeos reproduzidos via navegador web permitem a captura de áudio normalmente.*
>
> * **Jogos com Chat de Voz (VoIP):** Jogos competitivos que ativam o canal de chamada telefônica (`USAGE_VOICE_COMMUNICATION`) bloqueiam a captura na opção "Apenas Áudio Interno". Para gravar o som desses jogos, desative o microfone da equipe nas configurações do jogo ou utilize o modo **Áudio Interno + Microfone**.

## 🚀 Como Compilar e Instalar o Projeto

### Pré-requisitos

* **Android Studio** (versão Ladybug / Koala ou mais recente).

* **JDK 11** ou superior.

* Dispositivo físico Android (recomendado para testar o `MediaProjection` e o `AudioPlaybackCapture`).

### Passo a Passo para Gerar o APK

1. Clone este repositório no seu computador:

   ```
   git clone https://github.com/SEU-USUARIO/DexRecorder.git
   
   ```

2. Abra o **Android Studio**, clique em **Open** e selecione a pasta do projeto.

3. Aguarde a sincronização automática do Gradle (**Sync Project with Gradle Files**).

4. Para gerar o arquivo instalador de testes, vá no menu superior:

   * **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**
     *(Ou execute `./gradlew assembleDebug` no terminal).*

5. O arquivo final será gerado no caminho:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   
   ```

6. Transfira o `app-debug.apk` para o seu smartphone Android, instale e conceda as permissões iniciais solicitadas.

## 🔐 Permissões Utilizadas

Para operar em conformidade com o Android 13, 14 e 15 (One UI 5, 6 e 7), o aplicativo declara e solicita em tempo de execução:

* `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Obrigatórias para manter a captura de tela ativa em segundo plano.

* `FOREGROUND_SERVICE_MICROPHONE` & `RECORD_AUDIO`: Necessárias para inicializar o subsistema de captura de áudio interno e microfone.

* `POST_NOTIFICATIONS`: Exibe a barra de controle permanente com o tempo de gravação e botões de Pausa/Parada.

* `SYSTEM_ALERT_WINDOW`: Permite exibir a Bola Flutuante, a Facecam e a ferramenta de pincel sobre outros aplicativos.

## 🤝 Contribuições

Contribuições, relatos de *issues* e sugestões de melhorias são muito bem-vindos! Sinta-se à vontade para abrir uma *Issue* ou enviar um *Pull Request*.

## 📄 Licença

Este projeto está sob a licença MIT. Consulte o arquivo `LICENSE` para mais detalhes.

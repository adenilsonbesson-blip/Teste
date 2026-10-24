# Resumo do Projeto: CondoEntregas (v3.9)

## Principais Correções e Funcionalidades Recentes

1. **Correção de "Incompatible update" e Assinatura**:
   - Alteração do `applicationId` para `com.sice.condominio.appv3` para forçar uma nova instalação limpa.
   - Configuração de assinatura estrita usando uma chave persistente (`sice-app-key.jks`) na raiz do projeto.

2. **Prevenção de Conflitos de Dados**:
   - `android:allowBackup` definido como `false` para impedir conflitos de dados entre instalações.

3. **Painel Geral (Dashboard)**:
   - Removido o card "Média de Entregas Diárias por Bloco".
   - O status do Banco de Dados Integrado foi promovido a um card principal, com barra de progresso e porcentagem de uso em relação ao limite de 20 GB.

4. **Funcionalidade de Backup/Restauração Automática**:
   - Botão **"🛡️ Backup para Restauração Automática"** na seção "Área Admin > Backup & Sistema" para criar um arquivo de backup externo antes de desinstalar.
   - Na primeira inicialização após reinstalar, o app detecta e restaura o banco de dados automaticamente.

5. **Mesclagem Inteligente de Encomendas (Importação de Backup)**:
   - Ao importar um backup atualizado, se o sistema contiver um registro de encomenda com status pendente (`"Recebido"`) e o arquivo de backup trouxer o mesmo registro já com baixa (`"Entregue"`), o sistema atualiza automaticamente o registro existente com os dados de entrega.

6. **Segurança e Visualização de Backups no Admin**:
   - **Proteção por Senha na Seção de Backup**: Os botões de backup e restauração na Área Admin ficam ocultos por padrão, exigindo que o administrador digite a senha autorizada, cujo hash de validação é processado de forma criptografada no código ao clicar no botão "Exibir".
   - **Lista de Backups Organizada (Mais Recente ao Mais Antigo)**: A modal de importação de banco de dados exibe todos os backups gerados ordenados do mais recente ao mais antigo, cada um com uma caixa de seleção (`checkbox`) e um botão "Restaurar" ao lado.
   - **Formatação de Alta Legibilidade**: Lista formatada com fundo branco e letras pretas para excelente visualização.


7. **Exportação para PDF com Data e Hora da Entrega**:
   - Inclusão da coluna expressa **"Data e Hora da Entrega"** (exibindo a data e hora em que a encomenda foi retirada/entregue, ou "Pendente" caso ainda não tenha sido retirada) em conjunto com a coluna de data/hora de entrada.
   - Ajuste automático de orientação paisagem (A4 Landscape) e suporte a filtros ativos e seleção seletiva por checkbox.

8. **Exibição Total de Registros de Versões Anteriores (Relatório & PDF)**:
   - Resolução definitiva da omissão de registros ao importar backups de versões anteriores (.db ou .json).
   - Eliminação de colisão de IDs: encomendas importadas recebem identificadores únicos sequenciais preservando 100% dos dados sem sobrescrita indevida.
   - Normalização de colunas legadas (`destinatario`, `apartamento`, `codBarras`, `codigo`, `dataBaixa`, `horaBaixa`, `recebedor`, etc.).
   - Leitura de todas as chaves e tabelas SQLite do banco anterior (`web_data`, `encomendas`, `correspondencias`, `entregas`, `pacotes`).
   - Padrão do filtro de status configurado para **"📋 Todos os Registros (Geral - Padrão)"**, garantindo que todo o histórico (pendentes e entregues) apareça imediatamente na tela e no documento PDF.

9. **Consistência e Correção do Card "Entregas / Bloco / Mês" no Painel Geral**:
   - Unificação do algoritmo de apuração: o total e a distribuição por bloco no card agora utilizam exatamente o mesmo critério e função de validação de entregas do card "Entregue Mês Atual".
   - Suporte a múltiplos formatos de data (ISO `YYYY-MM-DD` e brasileiro `DD/MM/YYYY`) e fallback inteligente para `dataEntrada` quando a encomenda foi entregue sem preenchimento explícito do campo secundário de data.
   - Normalização de blocos (unifica variações como "BLOCO A", "Bloco A", "A", etc., impedindo contagens duplicadas ou fragmentadas).
   - Exibição do cabeçalho com o mês de vigência (`Mês: Out/26`), Total de entregas do mês e percentual proporcional de cada bloco. A soma dos blocos agora bate perfeitamente (100% precisa) com o card "Entregue Mês Atual".

10. **Geração Direta de PDF Vetorial (Sem Falhas do Spooler)**:
    - **Solução Definitiva para o Reinício do App e PDF Vazio**: O salvamento de PDF anteriormente dependia do `PrintSpooler` nativo do Android, que congelava com a interface do WebView (`pauseTimers` em `onPause` e reinício por `onRenderProcessGone`), gerando PDFs vazios ou fechando o aplicativo.
    - **Novo Motor de PDF Vetorial Integrado (`jsPDF` + `autoTable`)**: A geração agora ocorre 100% diretamente no aplicativo em memória de forma instantânea, sem depender do serviço de impressão externo do sistema operacional.
    - **Inclusão de 100% dos Registros**: Todos os registros filtrados ou selecionados são renderizados no documento, com quebras de página automáticas perfeitas, numeração de páginas ("Página X"), espaçamento entre blocos e destaque em vermelho para itens com entrega pendente.
    - **Cabeçalhos em Cinza e Negrito**: As colunas possuem fundo cinza moderno (`#6b7280`), fonte em negrito (`font-weight: bold;`) e texto em branco com alinhamento perfeito.
    - **Linhas Estreitas e Formatação de Data/Hora em Linha Única**: As colunas "Data/Hora Entrada" e "Data/Hora Entrega" agora exibem data e hora unidas por hífen na mesma linha (ex: `01/10/2026 - 12:00`), sem quebras em duas linhas verticais. Com padding ultra-compacto (`cellPadding: { top: 1.0, bottom: 1.0 }`), o espaço vertical foi drasticamente otimizado.
    - **Auto-Ajuste Inteligente de Colunas**: As colunas `Morador`, `Funcionário`, `Entreg.`, `Assinatura/QR` e `Observação` calculam suas larguras dinamicamente com base no comprimento real dos dados do conjunto filtrado, distribuindo proporcionalmente os 454 pt restantes da folha A4 Paisagem.
    - **Assinatura na Mesma Linha com Abreviações (Port e Entreg)**: A coluna de validação abrevia `Porteiro` para `Port:` e `Entregador` para `Entreg:` (ex: `Port: João | Entreg: Carlos`), e o algoritmo de largura reserva espaço prioritário para que a assinatura completa caiba sem quebra de linha.
    - **Sempre no Modo Paisagem (A4 Landscape)**: O relatório é gerado em orientação paisagem com aproveitamento total da largura (818 pt), garantindo o alinhamento impecável de todas as 12 colunas sem cortes.
    - **Tipografia Totalmente em Negrito**: Todo o conteúdo da tabela (cabeçalhos, dados de encomendas, blocos, status e rodapé) é renderizado em **negrito** de alta nitidez e contraste.
    - **Salvamento Automático em Downloads & Menu Compartilhar**: O PDF é gravado automaticamente na pasta pública de **Downloads** do aparelho e abre o menu nativo de compartilhamento/salvamento do Android (`Intent.ACTION_SEND` / `FileProvider`), permitindo envio direto por WhatsApp, Google Drive, e-mail ou visualização imediata no leitor de PDF.

11. **Bip Tradicional de Caixa de Supermercado (Honeywell / Zebra)**:
    - O som do leitor de código de barras e QR Code foi calibrado para o padrão clássico e agradável dos scanners de checkout de supermercados.
    - Utiliza tom senoidal puro a **1900 Hz** com duração ultrarrápida de **70 ms**, ataque suave de 4 ms (eliminando estalos nos alto-falantes) e volume moderado e confortável (amplitude reduzida para não incomodar ou soar estridente).
    - Implementado tanto no motor nativo do Android (`AudioTrack` PCM mono de baixa latência) quanto no Web Audio (`AudioContext`), garantindo feedback imediato, suave e profissional a cada leitura.

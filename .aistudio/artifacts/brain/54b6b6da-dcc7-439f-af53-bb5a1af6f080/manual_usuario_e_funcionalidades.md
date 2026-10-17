# Manual de Usuário e Guia de Funcionalidades - Sice Condomínio

Este documento apresenta a documentação completa de todas as telas, fluxos operacionais e recursos do **Sice Condomínio**, sem exibição de dados sensíveis ou senhas.

---

## 1. Visão Geral da Arquitetura
* **Tecnologia:** Aplicativo Android nativo em Kotlin com Jetpack Compose e motor WebView de alta performance.
* **Banco de Dados:** SQLite local otimizado (WAL mode) para armazenamento rápido e seguro de dados.
* **Segurança e Privacidade:** Todas as ações críticas exigem autenticação do administrador por senha.

---

## 2. Guia Detalhado Tela a Tela

### 2.1 Tela Inicial / Painel Geral (Dashboard)
* **Objetivo:** Centralizar os indicadores e o resumo operacional diário do condomínio.
* **Principais Funções:**
  * **Indicadores em Tempo Real:** Total de encomendas cadastradas, pendentes de retirada e já entregues.
  * **Gráficos de Movimentação:** Estatísticas de distribuição de entregas por bloco e por período.
  * **Status do Banco de Dados:** Medidor visual de capacidade e espaço utilizado no banco SQLite.
  * **Responsividade:** Layout adaptativo para visualização perfeita em smartphones e tablets.

### 2.2 Tela de Entrada / Cadastro de Encomendas e Exames
* **Objetivo:** Registrar o recebimento de novas correspondências, encomendas ou exames.
* **Principais Funções:**
  * **Seleção de Morador e Bloco:** Localização ágil do morador destinatário.
  * **Origem e Entregador:** Registro da transportadora, entregador ou laboratório de origem.
  * **Registro de Data/Hora:** Carimbo automático do momento exato da recepção.

### 2.3 Tela de Saídas e Baixas (Retirada)
* **Objetivo:** Finalizar o fluxo de entrega ao morador.
* **Principais Funções:**
  * **Assinatura Digital:** Área interativa para o morador ou recebedor assinar diretamente na tela do dispositivo.
  * **Identificação do Recebedor:** Campo para registrar o nome e o grau de parentesco ou relação com o morador.
  * **Atualização de Status:** Transição instantânea da encomenda de "Pendente" para "Entregue".

### 2.4 Tela de Relatórios e Consultas
* **Objetivo:** Consultar o histórico geral de correspondências.
* **Principais Funções:**
  * **Filtros por Status:** Separação imediata entre itens pendentes e entregues.
  * **Filtro por Bloco / Período:** Busca personalizada por intervalo de datas.
  * **Exportação para Excel:** Geração de planilha estruturada para controle externo.

### 2.5 Tela de Administração e Configurações de Backup
* **Objetivo:** Gerenciar a segurança, dados e cópias de segurança do sistema.
* **Principais Funções:**
  * **Exportação Manual de Backup:** Geração de arquivo de backup completo (`.db`) armazenado na pasta pública do dispositivo.
  * **Backup Automático Diário (07:00):** Rotina executada em segundo plano via WorkManager (funciona com o app fechado) para salvamento diário e envio opcional via webhook para o Google Drive.
  * **Tabela de Gerenciamento de Backups:** Listagem de arquivos de backup anteriores com dados de data, hora, tamanho e botão de restauração compacto.
  * **Exclusão Seletiva de Encomendas:** Opção protegida por senha de administrador para limpar o histórico de entregas mantendo 100% preservados os cadastros de moradores, porteiros e entregadores.

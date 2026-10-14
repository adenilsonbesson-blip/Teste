# 📦 CondoEntregas (SICE) - Versão 3.0 (Atualizada)

Documentação técnica e operacional completa do aplicativo **CondoEntregas (SICE) v3.0**, desenvolvido para controle inteligente de recepção, entrega e baixa de encomendas e exames em condomínios residenciais e comerciais.

---

## 🎯 Visão Geral da Versão 3.0 e Atualizações Recentes

A versão 3.0 consolida melhorias de usabilidade, alta performance na renderização de dados volumosos, persistência em camada tripla e interface otimizada para operação rápida na portaria.

### 🌟 Principais Recursos e Módulos

1. **🏠 Início & Painel Geral (Dashboard 100% em 2 Colunas)**
   - **Estrutura estrita em 2 Colunas em todas as resoluções**, mantendo a formatação e proporção visual:
     1. **Par 1:** Pendentes | Entregues Hoje
     2. **Par 2:** Entregue Mês Atual | Entregue Mês Anterior
     3. **Par 3:** Entregues Ano Atual | Entregues Ano Anterior
     4. **Destaque Central:** Total Geral de Encomendas
     5. **Par 4:** Entregues por Bloco / Mês | Média Semanal por Bloco
     6. **Destaque:** Média de Entregas Diárias por Bloco
     7. **Par 5 (Armazenamento):** Espaço Banco Usado (%) | Espaço Banco Vazio/Livre (%)
   - Acesso rápido aos fluxos principais de entrada, baixa e exames.

2. **🔍 Entrada Inteligente de Encomendas & Exames**
   - Leitura ultrarrápida de código de barras e QR Code via câmera nativa (ML Kit Barcode Scanning).
   - Associação automática de Morador x Bloco x Apartamento com base nos cadastros locais.
   - **Ocultação de Campos Auxiliares**: Os campos de nome do porteiro/entregador e data/hora ficam ocultos na tela principal, sendo preenchidos e gravados automaticamente ao realizar a baixa/leitura via QR Code.

3. **📋 Tela de Baixas (Interface v3.0 em Lista Contínua)**
   - **Card Superior de Quantidade do Bloco**: Exibe de forma clara e destacada o bloco selecionado, total de encomendas pendentes e contador de itens selecionados em tempo real.
   - **Formato de Lista Contínua**: Exibição estruturada em linhas com divisores limpos, cabeçalho de colunas idêntico ao relatório e seleção múltipla ágil com rolagem fluida por toque na tela inteira (`touch-action: pan-y`).
   - **Fluxo Seguro de Baixa com QR Code**:
     - *Passo 1*: Leitura do crachá/QR Code do Porteiro (preenchido automaticamente).
     - *Passo 2*: Leitura do crachá/QR Code do Entregador (preenchido automaticamente).

4. **🧪 Módulo de Exames com Avanço Automático Contínuo**
   - **Avanço Automático de Campos**:
     1. Ao selecionar ou preencher o **Laboratório / Clínica**, o foco avança automaticamente para o campo **Bloco**.
     2. Ao selecionar o **Bloco**, o sistema avança automaticamente para o campo **Apartamento**.
     3. Ao digitar o **Apartamento**, o sistema filtra os moradores e avança automaticamente para o campo **Morador** (selecionando-o de imediato caso exista apenas 1 morador cadastrado).
   - **Cabeçalho Otimizado**: Exibe de forma limpa e direta apenas **"Exames"**, economizando espaço vertical.
   - **Tabela de Registros Completa**: Exibição da tabela de registros de exames abaixo do formulário com rolagem fluida em todas as direções.
   - **Segurança Reforçada**: Exclusão protegida por senha de administrador.

5. **📊 Relatórios com Filtro Padrão de Pendentes**
   - **Carregamento Otimizado por Filtro**: Apenas carrega os registros estritamente compatíveis com os filtros aplicados.
   - **Filtro Padrão Inicial**: Ao abrir o relatório ou resetar filtros, exibe **somente as encomendas pendentes** (`Status: Recebido`).
   - Filtros combinados por Bloco, Apartamento, Código de Barras, Plataforma e Período de Datas.
   - Exportação em PDF e CSV (Excel) respeitando fielmente os filtros selecionados.

6. **👥 Gerenciamento de Cadastros & Segurança**
   - Cadastro e edição de Moradores, Apartamentos, Porteiros e Entregadores.
   - Proteção por senha do Administrador com liberação temporária (temporizador de 5 minutos com bloqueio automático).

---

## 🛠️ Arquitetura Técnica

- **Linguagem & Framework**: Kotlin + Jetpack Compose + Android WebView + HTML5/CSS3 Moderno.
- **Banco de Dados Local**: Android Room SQLite + SharedPreferences + Backup JSON atômico com medição precisa de tamanho em tempo real via `AndroidDB.getDatabaseSizeKB()` e capacidade configurada para **20 GB**.
- **Automação CI/CD GitHub Actions**: Arquivo `.github/workflows/build-apk.yml` automatizado para compilar e gerar os APKs (Debug e Release) a cada push no repositório.
- **Scanner de Câmera**: CameraX (`androidx.camera.core`) + Google ML Kit Vision Barcode Scanning.
- **Design System**: Material Design 3 com paleta azul institucional e verde de status de entrega, tipografia responsiva e layout adaptativo.

---

*CondoEntregas (SICE) v3.0 - Criado e mantido no Google AI Studio Build.*

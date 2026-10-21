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

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
   - Ao importar um backup atualizado, se o sistema contiver um registro de encomenda com status pendente (`"Recebido"`) e o arquivo de backup trouxer o mesmo registro já com baixa (`"Entregue"`), o sistema atualiza automaticamente o registro existente com os dados de entrega (data, hora, porteiro e assinatura).

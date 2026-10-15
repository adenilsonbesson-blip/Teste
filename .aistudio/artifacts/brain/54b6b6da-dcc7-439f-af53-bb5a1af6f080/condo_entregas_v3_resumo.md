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
   - Devido às restrições do sistema Android (que não permitem código rodar durante a desinstalação), foi implementado um fluxo de **Restauração Automática**:
     - **Backup Manual**: Antes de desinstalar, o usuário deve ir em "Área Admin > Backup" e clicar em **"Backup para Restauração Automática"**.
     - **Restauração Automática**: Ao instalar e abrir o app pela primeira vez, ele detecta o backup na pasta externa, restaura os dados automaticamente e remove o arquivo de backup.

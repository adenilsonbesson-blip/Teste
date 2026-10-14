import re

filepath = "/app/applet/app/src/main/assets/index.html"
with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Hide the select all checkbox in table header
old_checkbox = '<th style="width: 50px;"><input type="checkbox" id="check-all-relatorio" onclick="toggleTodosChecksRelatorio(this)"></th>'
new_checkbox = '<th style="width: 50px;"><input type="checkbox" id="check-all-relatorio" onclick="toggleTodosChecksRelatorio(this)" style="display: none;"></th>'

if old_checkbox in content:
    content = content.replace(old_checkbox, new_checkbox)
    print("Successfully hid check-all-relatorio")
else:
    print("Warning: old_checkbox not found exactly")

# 2. Update exportarRelatorioPdf to only export filtered items
old_export_func = """        function exportarRelatorioPdf(apenasSelecionados = false) {
            let listaDados = [];
            const masterCheck = document.getElementById('check-all-relatorio');
            const masterMarcado = masterCheck && masterCheck.checked;
            const totalBanco = (db.encomendas || []).length;
            const temSelecao = (idsSelecaoRelatorio && idsSelecaoRelatorio.length > 0);
            // Ao selecionar todos na caixa de seleção do cabeçalho, seleciona todos os registros existentes no banco de dados para exportar para PDF:
            if (masterMarcado || (temSelecao && idsSelecaoRelatorio.length >= totalBanco && totalBanco > 0)) {
                listaDados = [...(db.encomendas || [])];
            } else if (temSelecao) {
                listaDados = (db.encomendas || []).filter(e => idsSelecaoRelatorio.includes(e.id));
            } else if (apenasSelecionados) {
                return alert("Selecione os registros na tabela primeiro.");
            } else {
                const fStatus = document.getElementById('filtro-status')?.value;
                const fIni = document.getElementById('filtro-data-ini')?.value || ""; 
                const fFim = document.getElementById('filtro-data-fim')?.value || ""; 
                const fBloco = document.getElementById('filtro-bloco-select')?.value || ""; 
                const fApto = document.getElementById('filtro-apto') ? document.getElementById('filtro-apto').value.trim().toLowerCase() : "";
                const fRastreio = document.getElementById('filtro-rastreio') ? document.getElementById('filtro-rastreio').value.trim().toLowerCase() : "";
                const fPlat = document.getElementById('filtro-plataforma-select') ? document.getElementById('filtro-plataforma-select').value.trim().toLowerCase() : "";
                                
                listaDados = (db.encomendas || []).filter(e => {
                    if (fStatus && e.status !== fStatus) return false;
                    if (fIni && e.dataEntrada < fIni) return false; 
                    if (fFim && e.dataEntrada > fFim) return false; 
                    if (fBloco && e.bloco !== fBloco) return false; 
                    if (fApto && (!e.apto || e.apto.toLowerCase() !== fApto)) return false; 
                    if (fRastreio && (!e.rastreio || !e.rastreio.toLowerCase().startsWith(fRastreio))) return false;
                    if (fPlat && (!e.plataforma || !e.plataforma.toLowerCase().includes(fPlat))) return false;
                    return true;
                });
            }"""

new_export_func = """        function exportarRelatorioPdf(apenasSelecionados = false) {
            let listaDados = [];
            const fStatus = document.getElementById('filtro-status')?.value;
            const fIni = document.getElementById('filtro-data-ini')?.value || ""; 
            const fFim = document.getElementById('filtro-data-fim')?.value || ""; 
            const fBloco = document.getElementById('filtro-bloco-select')?.value || ""; 
            const fApto = document.getElementById('filtro-apto') ? document.getElementById('filtro-apto').value.trim().toLowerCase() : "";
            const fRastreio = document.getElementById('filtro-rastreio') ? document.getElementById('filtro-rastreio').value.trim().toLowerCase() : "";
            const fPlat = document.getElementById('filtro-plataforma-select') ? document.getElementById('filtro-plataforma-select').value.trim().toLowerCase() : "";
                            
            listaDados = (db.encomendas || []).filter(e => {
                if (fStatus && e.status !== fStatus) return false;
                if (fIni && e.dataEntrada < fIni) return false; 
                if (fFim && e.dataEntrada > fFim) return false; 
                if (fBloco && e.bloco !== fBloco) return false; 
                if (fApto && (!e.apto || e.apto.toLowerCase() !== fApto)) return false; 
                if (fRastreio && (!e.rastreio || !e.rastreio.toLowerCase().startsWith(fRastreio))) return false;
                if (fPlat && (!e.plataforma || !e.plataforma.toLowerCase().includes(fPlat))) return false;
                return true;
            });"""

if old_export_func in content:
    content = content.replace(old_export_func, new_export_func)
    print("Successfully updated exportarRelatorioPdf")
else:
    print("Warning: old_export_func not found exactly")

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
print("Done updating index.html")

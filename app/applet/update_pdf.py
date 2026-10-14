filepath = "/app/applet/app/src/main/assets/index.html"
with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

target_start = "function exportarRelatorioPdf(apenasSelecionados = false) {"
target_end = "            // Ordenar por bloco em crescente"

start_idx = content.find(target_start)
end_idx = content.find(target_end, start_idx)

if start_idx != -1 and end_idx != -1:
    new_body = """function exportarRelatorioPdf(apenasSelecionados = false) {
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
            });
"""
    content = content[:start_idx] + new_body + content[end_idx:]
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    print("Successfully replaced exportarRelatorioPdf body!")
else:
    print("Error finding exportarRelatorioPdf boundaries", start_idx, end_idx)

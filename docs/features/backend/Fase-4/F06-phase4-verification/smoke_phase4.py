"""Smoke da Fase 4 contra o backend ligado ao Supabase real (pooler 6543). Usa só a API; nenhuma credencial do banco.
Clinica ficticia 'teste-smoke-f3-p4b-<ts>' (cai no escopo de database/scripts/cleanup_smoke_phase3_phase4.sql).
Nao imprime tokens, senha, texto clinico nem o corpo do PDF. Grava dados no banco real: so rodar com confirmacao do usuario;
a limpeza fica com o usuario (como postgres)."""
import json
import secrets
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api/v1"
TS = str(int(time.time()))
EMAIL = f"smoke-f3-p4b-{TS}@teste.local"
PASSWORD = "Sm" + secrets.token_urlsafe(18) + "9a"  # senha nova a cada execucao; nunca impressa e nunca versionada
results = []


def call(method, path, body=None, token=None, raw=False):
    """Devolve (status, corpo, cabecalhos). Com raw=True o corpo vem em bytes (PDF)."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = resp.read()
            return resp.status, (payload if raw else (json.loads(payload) if payload else None)), resp.headers
    except urllib.error.HTTPError as err:
        payload = err.read()
        try:
            return err.code, json.loads(payload), err.headers
        except Exception:
            return err.code, None, err.headers


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" -- " + detail if detail else ""))
    sys.stdout.flush()


# --- registro, CRO e paciente ---
status, session, _ = call("POST", "/auth/register", {"clinicName": f"teste-smoke-f3-p4b-{TS}", "name": "Dra. Smoke", "email": EMAIL, "password": PASSWORD})
check("registro da clinica ficticia", status == 201, f"status={status}")
token = session["accessToken"]
status, me, _ = call("GET", "/me", token=token)
check("GET /me traz version e CRO nulo", status == 200 and me.get("cro") is None and me.get("version") is not None, f"status={status}")
status, patient, _ = call("POST", "/patients", {"name": "Paciente Smoke Fase Quatro", "birthDate": "1988-04-12", "phone": "11987654321"}, token)
check("cria paciente", status == 201, f"status={status}")
pid = patient["id"]
list_url = f"/patients/{pid}/clinical-documents"

status, body, _ = call("POST", list_url, {"documentType": "PRESCRIPTION", "content": "Dipirona 500 mg"}, token)
check("emitir sem CRO e recusado com 409", status == 409, f"status={status}")
status, me, _ = call("PUT", "/me/professional-registry", {"cro": "12345", "croState": "SP", "version": me["version"]}, token)
check("grava o CRO", status == 200 and me.get("cro") == "12345" and me.get("croState") == "SP", f"status={status}")

# --- emissao dos tres tipos ---
status, cert, _ = call("POST", list_url, {"documentType": "CERTIFICATE", "restDays": 2, "content": "Repouso relativo"}, token)
check("emite atestado numero 1", status == 201 and cert["number"] == 1 and cert["state"] == "ACTIVE", f"status={status}")
status, rx, _ = call("POST", list_url, {"documentType": "PRESCRIPTION", "content": "Amoxicilina 500 mg de 8 em 8 horas"}, token)
check("emite receita numero 1", status == 201 and rx["number"] == 1, f"status={status}")
status, ref, _ = call("POST", list_url, {"documentType": "REFERRAL", "targetSpecialty": "Endodontia", "content": "Dente 36"}, token)
check("emite encaminhamento numero 1", status == 201 and ref["number"] == 1, f"status={status}")
status, bad, _ = call("POST", list_url, {"documentType": "CERTIFICATE"}, token)
check("atestado sem dias e 400", status == 400, f"status={status}")
status, bad, _ = call("POST", list_url, {"documentType": "PRESCRIPTION", "content": "x", "issuedOn": "2999-01-01"}, token)
check("data futura e 422", status == 422, f"status={status}")

# --- numeracao em paralelo atras do pooler 6543 (advisory lock cdoc:) ---
numbers, codes = [], []
lock = threading.Lock()


def emit():
    st, body, _ = call("POST", list_url, {"documentType": "CERTIFICATE", "restDays": 1}, token)
    with lock:
        codes.append(st)
        if st == 201:
            numbers.append(body["number"])


threads = [threading.Thread(target=emit) for _ in range(6)]
[t.start() for t in threads]
[t.join() for t in threads]
check("6 emissoes simultaneas: todas 201", codes.count(201) == 6, f"status={sorted(codes)}")
check("numeros distintos e sequenciais (2 a 7)", sorted(numbers) == list(range(2, 8)), f"numeros={sorted(numbers)}")

# --- leitura, lista, anulacao ---
status, detail, _ = call("GET", f"/clinical-documents/{cert['id']}", token=token)
check("detalhe devolve o texto decifrado", status == 200 and detail["content"] == "Repouso relativo", f"status={status}")
status, page, _ = call("GET", list_url + "?size=50", token=token)
check("lista traz os 9 documentos sem texto", status == 200 and page["totalElements"] == 9 and "content" not in page["content"][0], f"status={status}")
status, voided, _ = call("POST", f"/clinical-documents/{rx['id']}/void", {"version": rx["version"], "reason": "Emitida por engano"}, token)
check("anula a receita", status == 200 and voided["state"] == "VOIDED", f"status={status}")
status, _, _ = call("POST", f"/clinical-documents/{rx['id']}/void", {"version": voided["version"]}, token)
check("anular de novo e 409", status == 409, f"status={status}")

# --- PDF ---
status, pdf, headers = call("GET", f"/clinical-documents/{cert['id']}/pdf", token=token, raw=True)
check("PDF do atestado: 200, %PDF e cabecalhos", status == 200 and pdf[:5] == b"%PDF-" and headers.get("Content-Type") == "application/pdf"
      and "inline" in headers.get("Content-Disposition", "") and headers.get("Cache-Control") == "no-store", f"status={status} bytes={len(pdf or b'')}")
status, pdf_voided, _ = call("GET", f"/clinical-documents/{rx['id']}/pdf", token=token, raw=True)
check("PDF de documento anulado: 200", status == 200 and pdf_voided[:5] == b"%PDF-", f"status={status}")

# --- auditoria ---
status, audit, _ = call("GET", f"/audit-log?tableName=clinical_documents&recordId={cert['id']}", token=token)
actions = sorted(entry["action"] for entry in (audit or {}).get("content", []))
check("auditoria do atestado: INSERT e DOWNLOAD", status == 200 and actions == ["DOWNLOAD", "INSERT"], f"acoes={actions}")
status, audit, _ = call("GET", f"/audit-log?tableName=clinical_documents&recordId={rx['id']}", token=token)
actions = sorted(entry["action"] for entry in (audit or {}).get("content", []))
check("auditoria da receita: DOWNLOAD, INSERT e VOID", status == 200 and actions == ["DOWNLOAD", "INSERT", "VOID"], f"acoes={actions}")

# --- dashboard ---
status, dash, _ = call("GET", "/dashboard", token=token)
check("dashboard do dentista traz faturamento e 7 dias", status == 200 and "monthlyRevenue" in dash and len(dash["weekly"]["days"]) == 7, f"status={status}")
check("dashboard: pacientes ativos = 1", dash.get("totalPatients") == 1)
status, dash, _ = call("GET", "/dashboard?weekOf=1999-12-31", token=token)
check("weekOf fora do intervalo e 422", status == 422, f"status={status}")

# --- anonimizacao ---
status, _, _ = call("PATCH", f"/patients/{pid}/active", {"active": False}, token)
check("inativa o paciente", status == 200, f"status={status}")
status, _, _ = call("POST", f"/patients/{pid}/anonymize", token=token)
check("anonimiza o paciente", status == 200, f"status={status}")
status, anon, _ = call("GET", f"/clinical-documents/{cert['id']}", token=token)
check("documento anonimizado: sem texto, linha e numero mantidos", status == 200 and anon["anonymized"] is True and anon["content"] is None and anon["number"] == 1, f"status={status}")
status, _, _ = call("GET", f"/clinical-documents/{cert['id']}/pdf", token=token, raw=True)
check("PDF de documento anonimizado e 409", status == 409, f"status={status}")

failed = [name for name, ok, _ in results if not ok]
print(f"RESULTADO: {len(results) - len(failed)}/{len(results)} verificacoes OK" + (f"; falhas: {failed}" if failed else ""))
sys.exit(1 if failed else 0)

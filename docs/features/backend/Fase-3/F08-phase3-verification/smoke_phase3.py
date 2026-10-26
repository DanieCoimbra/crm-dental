"""Smoke da Fase 3 contra o backend ligado ao Supabase real (pooler 6543). Usa só a API; nada de credenciais do banco.
Clinica fictícia 'teste-smoke-f3-<ts>'. Não imprime tokens, valores de segredo nem URLs assinadas."""
import json
import secrets
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api/v1"
TS = str(int(time.time()))
EMAIL = f"smoke-f3-{TS}@teste.local"
PASSWORD = "Sm" + secrets.token_urlsafe(18) + "9a"  # senha nova a cada execução; nunca impressa e nunca versionada
results = []


def call(method, path, body=None, token=None, raw=False):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = resp.read()
            return resp.status, (payload if raw else (json.loads(payload) if payload else None))
    except urllib.error.HTTPError as err:
        payload = err.read()
        try:
            return err.code, json.loads(payload)
        except Exception:
            return err.code, None


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" -- " + detail if detail else ""))
    sys.stdout.flush()


# --- registro e login ---
status, session = call("POST", "/auth/register", {"clinicName": f"teste-smoke-f3-{TS}", "name": "Dra. Smoke", "email": EMAIL, "password": PASSWORD})
check("registro da clinica fictícia", status == 201, f"status={status}")
token = session["accessToken"]

status, patient = call("POST", "/patients", {"name": "Paciente Smoke Fase Tres", "birthDate": "1988-04-12", "phone": "11987654321"}, token)
check("cria paciente", status == 201, f"status={status}")
pid = patient["id"]

# --- catalogo ---
status, rest = call("POST", "/procedure-types", {"name": "Restauracao smoke", "basePrice": "250.00", "defaultDurationMinutes": 45, "color": "#3B82F6"}, token)
check("cria procedimento", status == 201, f"status={status}")
status, root = call("POST", "/procedure-types", {"name": "Canal smoke", "basePrice": "900.00", "defaultDurationMinutes": 90, "color": "#EF4444"}, token)

# --- numeração de orçamento em paralelo (advisory lock atras do pooler 6543) ---
numbers, statuses = [], []
lock = threading.Lock()


def create_budget():
    st, body = call("POST", "/budgets", {"patientId": pid, "items": [{"procedureTypeId": rest["id"], "quantity": 1}]}, token)
    with lock:
        statuses.append(st)
        if st == 201:
            numbers.append((body["number"], body["id"], body["version"]))


threads = [threading.Thread(target=create_budget) for _ in range(6)]
[t.start() for t in threads]
[t.join() for t in threads]
nums = sorted(n for n, _, _ in numbers)
check("numeracao concorrente (6 orcamentos): todos 201 e numeros distintos e sequenciais",
      statuses.count(201) == 6 and nums == list(range(nums[0], nums[0] + 6)), f"status={sorted(statuses)} numeros={nums}")

# --- orçamento principal: itens, desconto, envio (PDF no Storage real) ---
status, budget = call("POST", "/budgets", {"patientId": pid, "notes": "obs smoke", "discountAmount": "600.00",
                                            "items": [{"procedureTypeId": rest["id"], "quantity": 2}, {"procedureTypeId": root["id"], "quantity": 1}]}, token)
check("orcamento: total calculado pelo banco (2x250 + 900 - 600 = 800.00)", status == 201 and budget["totalAmount"] == "800.00", f"total={budget and budget.get('totalAmount')}")
bid = budget["id"]
status, sent = call("POST", f"/budgets/{bid}/send", {"version": budget["version"]}, token)
check("envio: SENT com PDF revisao 1 (upload no Storage real dentro da transacao)", status == 200 and sent["status"] == "SENT" and len(sent["documents"]) == 1, f"status={status}")
doc = sent["documents"][0]["id"]
status, url = call("GET", f"/budgets/{bid}/documents/{doc}/download-url", token=token)
pdf_ok = False
if status == 200:
    try:
        with urllib.request.urlopen(url["url"], timeout=60) as resp:
            head = resp.read(5)
            pdf_ok = resp.status == 200 and head == b"%PDF-"
    except Exception as err:
        pdf_ok = False
check("URL assinada baixa um PDF do Storage real", pdf_ok)
status, regen = call("POST", f"/budgets/{bid}/documents", token=token)
check("reemissao do PDF (revisao 2)", status == 201 and regen["revision"] == 2, f"status={status}")

# --- decisao e plano de parcelas: constraint trigger adiado (CR011) atras do pooler ---
status, appr = call("POST", f"/budgets/{bid}/approve", {"version": sent["version"]}, token)
check("aprovacao", status == 200 and appr["status"] == "APPROVED", f"status={status}")
status, plan = call("POST", f"/budgets/{bid}/installments", {"mode": "AUTO", "count": 3, "firstDueDate": "2027-03-01", "downPayment": "200.00"}, token)
total = sum(float(i["amount"]) for i in plan["installments"]) if status == 201 else 0
check("plano AUTO com entrada (CR011 conferido no commit): 201 e soma == 800.00", status == 201 and abs(total - 800.0) < 0.001, f"status={status}")
status, again = call("POST", f"/budgets/{bid}/installments", {"mode": "AUTO", "count": 2, "firstDueDate": "2027-03-01"}, token)
check("segundo plano: 409", status == 409, f"status={status}")
status, bad = call("PUT", f"/budgets/{bid}/installments", {"mode": "MANUAL", "installments": [{"amount": "700.00", "dueDate": "2027-03-01"}]}, token)
check("plano manual com soma errada: 400", status == 400, f"status={status}")
installments = plan["installments"]
i1, i2, i3 = (i["id"] for i in installments)

# --- pagamentos: último saldo em paralelo (trava da parcela + trigger atras do pooler) ---
status, p1 = call("POST", f"/installments/{i1}/payments", {"amount": "50.00", "method": "PIX"}, token)
check("pagamento parcial", status == 201 and p1["installment"]["status"] == "OPEN" and p1["installment"]["netPaid"] == "50.00", f"status={status}")
race = []


def pay_rest():
    st, body = call("POST", f"/installments/{i2}/payments", {"amount": installments[1]["amount"], "method": "CASH"}, token)
    with lock:
        race.append(st)


threads = [threading.Thread(target=pay_rest) for _ in range(2)]
[t.start() for t in threads]
[t.join() for t in threads]
check("dois pagamentos simultaneos no ultimo saldo: um 201 e um 409", sorted(race) == [201, 409], f"status={sorted(race)}")
status, listed = call("GET", f"/budgets/{bid}/installments", token=token)
second = next(i for i in listed["installments"] if i["id"] == i2)
check("parcela quitada por trigger (PAID)", second["status"] == "PAID", f"status={second['status']}")
paid_id = None
status, hist = call("GET", f"/installments/{i2}/payments", token=token)
paid_id = hist[0]["id"] if status == 200 and hist else None
status, rev = call("POST", f"/payments/{paid_id}/reversal", token=token)
check("estorno reabre a parcela (OPEN)", status == 201 and rev["installment"]["status"] == "OPEN", f"status={status}")
status, rev2 = call("POST", f"/payments/{paid_id}/reversal", token=token)
check("segundo estorno: 409", status == 409, f"status={status}")

# --- relatorios e ficha ---
status, rec = call("GET", "/finance/receivables?from=2027-01-01&to=2027-12-31", token=token)
check("a receber", status == 200 and rec["summary"]["count"] >= 1, f"status={status}")
status, flow = call("GET", f"/finance/cash-flow?from=2026-01-01&to=2026-12-31", token=token)
check("fluxo de caixa liquido", status == 200, f"status={status}")
status, rev_report = call("GET", "/finance/revenue?from=2026-01-01&to=2026-12-31", token=token)
check("faturamento", status == 200 and rev_report["count"] >= 1, f"status={status}")
status, sheet = call("GET", f"/patients/{pid}/financial", token=token)
check("ficha financeira", status == 200, f"status={status}")

# --- anonimizacao: CR006 bloqueia; quitado, passa e apaga os PDFs do Storage real ---
call("PATCH", f"/patients/{pid}/active", {"active": False, "version": patient["version"]}, token)
status, blocked = call("POST", f"/patients/{pid}/anonymize", token=token)
check("anonimizar com parcela em aberto: 409 (CR006)", status == 409 and "parcelas em aberto" in (blocked or {}).get("detail", ""), f"status={status}")
status, listed = call("GET", f"/budgets/{bid}/installments", token=token)
for i in listed["installments"]:
    if float(i["balance"]) > 0:
        call("POST", f"/installments/{i['id']}/payments", {"amount": i["balance"], "method": "PIX"}, token)
status, anon = call("POST", f"/patients/{pid}/anonymize", token=token)
if status == 409 and "retenc" in json.dumps(anon).lower():
    check("anonimizar: retencao legal vigente (CR002) impede, como esperado para paciente novo com historico", True, "409 CR002")
else:
    check("anonimizacao conclui com limpeza do Storage", status == 200 and anon.get("storageCleanup") == "COMPLETED", f"status={status} cleanup={anon and anon.get('storageCleanup')}")
status, audit = call("GET", "/audit-log?tableName=payments&size=10", token=token)
check("auditoria de pagamentos", status == 200 and audit["totalElements"] >= 3, f"status={status}")

failed = [r for r in results if not r[1]]
print(f"\nRESULTADO: {len(results) - len(failed)}/{len(results)} verificacoes OK; clinica = teste-smoke-f3-{TS}")
sys.exit(1 if failed else 0)

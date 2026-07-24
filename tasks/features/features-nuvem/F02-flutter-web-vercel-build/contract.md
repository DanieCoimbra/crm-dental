# Feature Contract: F02 - Flutter Web & Vercel Build Optimization

**Feature:** `F02-flutter-web-vercel-build`  
**Versão:** 1.0.0  
**Consumidores Primários:** Plataforma de Hospedagem Vercel (Build Engine), Usuários Web (Navegador).  
**Localização:** `features/F02-flutter-web-vercel-build/contract.md`  

---

### 1. Contract Summary

Este contrato especifica as garantias do script de build do Flutter Web na Vercel e o comportamento do cliente HTTP `Dio` para comunicação com o Backend Go no Render.

---

### 2. Inputs (Requirements & Build Environment)

- **Variável de Compilação:** `--dart-define=API_BASE_URL=https://crm-clinica-gjss.onrender.com`
- **Ferramentas de Build Requeridas:**
  - Git
  - Flutter SDK (Branch `stable`)
  - Bash Shell (`build.sh`)

---

### 3. Outputs (Build Artifacts & Outbound Protocol)

#### 3.1 Artefatos de Compilação
- **Diretório de Saída:** `build/web/`
- **Ponto de Entrada Static:** `index.html` e pacotes compilados `main.dart.js` / CanvasKit.

#### 3.2 Protocolo de Saída HTTP (Dio Client)
- **Cabeçalhos Padrão Enviados:**
  - `Content-Type`: `application/json`
  - `Accept`: `application/json`
  - `Authorization`: `Bearer <jwt_token>` (quando autenticado)
- **Timeout Estrito:** 30 segundos para conexão e recepção.

---

### 4. Business Rules & Limits

1. **Validação da URL Base:**
   - Todas as requisições HTTP do Flutter Web devem apontar estritamente para `$API_BASE_URL/api/v1`.
2. **Reescrita SPA Imprescindível:**
   - Toda rota requisitada no servidor Vercel que não corresponda a um arquivo estático deve ser redirecionada internamente para `/index.html` com código de retorno 200.

---

### 5. Events Emitted (Side Effects)

- **`Auth.SessionExpired`:** Ao receber uma resposta HTTP 401 Unauthorized da API backend, o cliente Dio apaga a chave `jwt_token` do secure storage e emite o evento de logout para o Riverpod `authProvider`.

---

### 6. Acceptance Criteria (Integration Tests)

- [ ] Execução de `./build.sh` termina com status code `0` gerando o diretório `build/web`.
- [ ] O token JWT armazenado é anexado no cabeçalho `Authorization` de todas as chamadas autenticadas.
- [ ] Atualização direta de página (F5) em rotas profundas da aplicação não gera erro 404 na Vercel.

# Contract — F01 Extensão `btree_gist` e fuso da clínica (Fase 2)

## 1. Resumo
- **Feature:** F01-extensions-and-timezone (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** operação (bootstrap); F02 (precisa da extensão); F03 (hora local); backend (lê e altera o fuso).

## 2. Entradas

**Bootstrap (operação, como `postgres`):** `psql -v ON_ERROR_STOP=1 -f bootstrap/20_extensions.sql`, após `00_roles_and_schema.sql`. Reexecutável.

**Escrita do fuso (backend):** `UPDATE core.clinics SET timezone = ? WHERE id = <tenant>` como `crm_app`, com `app.clinic_id`, `app.role` e `app.user_id` definidos na mesma transação.

| Campo | Tipo | Regra |
|---|---|---|
| `timezone` | `varchar(50)` | Nome IANA existente em `pg_timezone_names` (ex.: `America/Sao_Paulo`, `UTC`); mínimo 3 caracteres |

## 3. Saídas

| Coluna | Tipo | Significado |
|---|---|---|
| `core.clinics.timezone` | `varchar(50)`, `NOT NULL`, default `'America/Sao_Paulo'` | Fuso da clínica; o backend converte "hoje" e "a semana" para `[início, fim)` em `timestamptz` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23514` | Fuso inexistente ou com menos de 3 caracteres | 422 |
| `42501` | Papel diferente de `DENTIST_ADMIN` tentando mudar o fuso | 403 |

## 4. Regras e limites
- Só `DENTIST_ADMIN` muda o fuso; reenviar o valor atual é permitido a qualquer papel que já possa atualizar `clinics`.
- O banco não calcula "hoje" nem a semana; o fuso é só dado de entrada do backend.
- A extensão `btree_gist` vive em `extensions`; `crm_app`, `crm_backup` e `crm_definer` não recebem `USAGE` nesse schema.
- Sem `btree_gist` não existe a Fase 2 (sem plano B).
- Clínicas existentes e novas começam com `America/Sao_Paulo`; o cadastro (V006) não recebe o fuso.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 73: default `America/Sao_Paulo`; `UTC` passa; `Mars/Phobos` falha (`23514`); recepção trocando → `42501`; recepção reenviando o mesmo valor passa; dentista troca; clínicas existentes recebem o default.
- [ ] Teste 83 (parte): bootstrap idempotente (duas execuções); `btree_gist` em `extensions`.
- [ ] Teste 82 (parte): V014 aplica do zero e `U014` reverte e reaplica.

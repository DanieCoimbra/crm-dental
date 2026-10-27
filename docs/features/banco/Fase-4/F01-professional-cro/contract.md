# Contract — F01 CRO do profissional (Fase 4)

## 1. Resumo
- **Feature:** F01-professional-cro (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** backend (perfil do dentista); F02 (guarda `CR012`).

## 2. Entradas
O backend define `app.clinic_id`, `app.role` e `app.user_id` na transação. `UPDATE` de `core.users` como `crm_app`, sobre a **própria** linha:

```json
{
  "id": "uuid (= app.user_id)",
  "cro_number": "string, 1 a 15 dígitos, ou null",
  "cro_state": "string, UF em maiúsculas, ou null (os dois ou nenhum)",
  "version": "bigint (bloqueio otimista)"
}
```

## 3. Saídas
| Coluna | Tipo | Observação |
|---|---|---|
| `cro_number` | `varchar(15)` | nulo até o dentista informar |
| `cro_state` | `varchar(2)` | idem |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23514` | UF inválida, número fora do formato, par incompleto, recepcionista com CRO | 422 |
| `42501` | alterar o CRO de outro usuário | 403 |

## 4. Regras e limites
- Só o dentista tem CRO; a recepção nunca.
- O backend mapeia as duas colunas na entidade `User` e as expõe só no perfil do próprio dentista.
- Sem `UNIQUE`: o mesmo CRO pode existir em clínicas diferentes.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 125: `CHECK`s do CRO (UF, formato, par, recepcionista).
- [ ] Teste 126: só o dono altera o CRO; demais atualizações de `users` intactas.
- [ ] Teste 140 (parte): `V031` aplica do zero; `U031` reverte e reaplica (destrutiva por convenção: o dono não enxerga linhas sob RLS FORCE, então não confere se há CRO gravado; com dados, restaurar do pg_dump).

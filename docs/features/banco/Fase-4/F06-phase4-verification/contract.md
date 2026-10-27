# Contract — F06 Verificação da Fase 4 (banco)

## 1. Resumo
- **Feature:** F06-phase4-verification (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** QA (`qa-feature-legacy`), backend da Fase 4 (liberação de `ddl-auto=validate`), operação do Supabase real.

## 2. Entradas
- Migrations `V031`–`V035` e rollbacks `U031`–`U035` das F01–F05.
- Harness: PostgreSQL descartável, `POSTGRES_PASSWORD` exportada, Docker ligado.
- Real: backup prévio, senha do `crm_owner` (porta 5432) e acesso ao SQL Editor como `postgres` (do usuário).

## 3. Saídas
| Entregável | Critério |
|---|---|
| Suíte 125–142 | verde, 0 falhas, dentro de `tests/run_all.sh` |
| Teste 143 | marcado como dependência do backend |
| `MIGRATIONS_EXPECTED` | `35` |
| README e specs | Fase 4 e V030 documentadas |
| Relatório | resultado por teste; no real, o que foi conferido |

### Erros / reprovações
| Situação | Resultado |
|---|---|
| Qualquer teste vermelho | fase reprovada |
| Isolamento: linha de outra clínica visível ou gravável | reprovada |
| `crm_app` com DDL, `BYPASSRLS` ou `GRANT ALL` | reprovada |
| Rollback ou reaplicação deixando resíduo | reprovada |
| Real não conferido | fase aprovada **com ressalva** registrada (não aplicada) |

## 4. Regras e limites
- Migration aplicada é imutável; correção = nova versão.
- Sem commit, push ou PR sem pedido do usuário.
- CI/CD segue adiado; nada disso roda em workflow.
- Pendências fora do banco (senha do `postgres`, bucket, guarda da chave AES) ficam registradas, não bloqueiam.
- Dados do projeto são fictícios (portfólio); os riscos de produção real são aceitos e documentados.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Testes 125–142 verdes no harness completo.
- [ ] Teste 138: isolamento (duas clínicas e sem `app.clinic_id`).
- [ ] Teste 139: permissões e catálogo de RLS.
- [ ] Teste 140: migrações, cadeia de rollback e função restaurada.
- [ ] Teste 141: dicionário de esquema.
- [ ] Teste 142: backup e restauração.
- [ ] V031–V035 aplicadas e conferidas no Supabase real, ou ressalva registrada.
- [ ] Smoke da Fase 3 refeito; `23P01` e `CR004` no real; V030 e Fase 4 documentadas.
